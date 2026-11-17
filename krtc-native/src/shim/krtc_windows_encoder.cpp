// Media Foundation hardware H.264. All COM/MFT calls stay on a dedicated MTA thread.
#include "krtc_windows_encoder.h"
#include "krtc_h264_bitstream.h"
#include "api/video_codecs/h264_profile_level_id.h"
#include "api/video_codecs/video_encoder_software_fallback_wrapper.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/thread.h"
#include "rtc_base/time_utils.h"
#include "libyuv/convert_from.h"
#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <codecapi.h>
#include <strmif.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <thread>
#include <mutex>
#include <deque>


namespace krtc {
namespace {
using Microsoft::WRL::ComPtr;
using namespace webrtc;

// Process-wide, bounded diagnostics survive encoder teardown and are sampled with getStats.
std::mutex diagnosticMutex;
std::deque<std::pair<uint64_t, std::string>> diagnosticEvents;
uint64_t diagnosticSequence = 0;
void Diagnostic(const std::string& message) {
    std::lock_guard<std::mutex> lock(diagnosticMutex);
    diagnosticEvents.emplace_back(++diagnosticSequence, "tMs=" + std::to_string(TimeMillis()) + " tid=" + std::to_string(GetCurrentThreadId()) + " " + message);
    if (diagnosticEvents.size() > 64) diagnosticEvents.pop_front();
    std::fprintf(stderr, "krtc encoder: %s\n", message.c_str());
}
bool Check(HRESULT hr, const char* operation) {
    if (SUCCEEDED(hr)) return true;
    char code[16];
    std::snprintf(code, sizeof(code), "0x%08lX", static_cast<unsigned long>(hr));
    Diagnostic(std::string(operation) + " failed hr=" + code);
    return false;
}
std::string JsonString(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '\\' || c == '"') out += '\\';
        if (c < 32) { char hex[7]; std::snprintf(hex, sizeof(hex), "\\u%04x", c); out += hex; }
        else out += static_cast<char>(c);
    }
    return out + "\"";
}

// Dynamic loading also keeps the complete krtc DLL usable on Windows N without Media Feature Pack.
struct MfApi {
    HMODULE dll = LoadLibraryExW(L"mfplat.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
#define MF_FUNCTION(name) decltype(&name) name = dll ? reinterpret_cast<decltype(&::name)>(GetProcAddress(dll, #name)) : nullptr
    MF_FUNCTION(MFStartup);
    MF_FUNCTION(MFShutdown);
    MF_FUNCTION(MFTEnumEx);
    MF_FUNCTION(MFCreateMediaType);
    MF_FUNCTION(MFCreateSample);
    MF_FUNCTION(MFCreateAlignedMemoryBuffer);
#undef MF_FUNCTION
    bool available() const {
        return MFStartup && MFShutdown && MFTEnumEx && MFCreateMediaType && MFCreateSample &&
               MFCreateAlignedMemoryBuffer;
    }
    ~MfApi() { if (dll) FreeLibrary(dll); }
};
MfApi& Api() { static MfApi api; return api; }

struct MfSession {
    HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool started = Check(com, "CoInitializeEx") && Api().available() && Check(Api().MFStartup(MF_VERSION, MFSTARTUP_FULL), "MFStartup");
    MfSession() { if (!Api().available()) Diagnostic("Media Foundation DLL/entry points unavailable; mfplatLoaded=" + std::to_string(Api().dll != nullptr)); }
    ~MfSession() {
        if (started) Api().MFShutdown();
        if (SUCCEEDED(com)) CoUninitialize();
    }
};

struct Activations {
    IMFActivate** items = nullptr;
    UINT32 count = 0;
    explicit Activations(bool enabled) {
        MFT_REGISTER_TYPE_INFO input{MFMediaType_Video, MFVideoFormat_NV12};
        MFT_REGISTER_TYPE_INFO output{MFMediaType_Video, MFVideoFormat_H264};
        if (enabled) Check(Api().MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER,
            MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, &input, &output, &items, &count), "MFTEnumEx NV12/H264 hardware");
        Diagnostic("hardware MFT candidates=" + std::to_string(count));
    }
    ~Activations() {
        for (UINT32 i = 0; i < count; ++i) items[i]->Release();
        CoTaskMemFree(items);
    }
};

bool HardwareAvailable() {
    static const bool available = [] {
        bool found = false;
        std::thread([&] { MfSession session; Activations list(session.started); found = list.count != 0; }).join();
        return found;
    }();
    return available;
}

bool HardwareFormat(const SdpVideoFormat& format) {
    const auto profile = ParseSdpForH264ProfileLevelId(format.parameters);
    const auto mode = format.parameters.find("packetization-mode");
    return format.name == "H264" && profile && profile->profile == H264Profile::kProfileConstrainedBaseline &&
           mode != format.parameters.end() && mode->second == "1";
}

class MfH264Encoder final : public VideoEncoder {
public:
    MfH264Encoder() : thread_(Thread::Create()) {
        thread_->SetName("krtc-mf-h264", nullptr);
        running_ = thread_->Start();
    }
    ~MfH264Encoder() override { Release(); }

    int InitEncode(const VideoCodec* codec, const Settings&) override {
        if (!running_ || !codec || !codec->width || !codec->height || codec->width % 2 || codec->height % 2 ||
            codec->numberOfSimulcastStreams > 1) {
            Diagnostic("hardware init rejected: invalid/odd dimensions, simulcast or worker unavailable");
            return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
        }
        Diagnostic("hardware init " + std::to_string(codec->width) + "x" + std::to_string(codec->height) +
            " fps=" + std::to_string(codec->maxFramerate) + " startKbps=" + std::to_string(codec->startBitrate));
        const bool ok = thread_->BlockingCall([&] {
            Reset();
            width_ = codec->width;
            height_ = codec->height;
            fps_ = std::max(1u, codec->maxFramerate);
            bitrate_ = std::max(1u, codec->startBitrate) * 1000u;
            session_ = std::make_unique<MfSession>();
            candidates_ = std::make_unique<Activations>(session_->started);
            if (TryNextTransform()) return true;
            Reset();
            return false;
        });
        if (!ok) Diagnostic("hardware init failed; fallback=software");
        return ok ? WEBRTC_VIDEO_CODEC_OK : WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
    }

    int32_t RegisterEncodeCompleteCallback(EncodedImageCallback* callback) override {
        callback_ = callback;
        return WEBRTC_VIDEO_CODEC_OK;
    }
    int32_t Release() override {
        if (running_) thread_->BlockingCall([&] { Reset(); });
        return WEBRTC_VIDEO_CODEC_OK;
    }
    int32_t Encode(const VideoFrame& frame, const std::vector<VideoFrameType>* types) override {
        if (!callback_) return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
        if (!running_) return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
        const int64_t encodeStart = TimeMillis();
        const bool key = types && std::find(types->begin(), types->end(), VideoFrameType::kVideoFrameKey) != types->end();
        bool paused = false;
        std::optional<EncodedImage> image = thread_->BlockingCall([&] {
            paused = paused_;
            if (paused) return std::optional<EncodedImage>{};
            auto image = EncodeFrame(frame, key);
            while (!image && candidates_ && nextCandidate_ < candidates_->count) {
                Diagnostic("hardware encode failed at " + stage_ + "; trying another hardware MFT");
                ResetTransform();
                if (!TryNextTransform()) break;
                // The replacement encoder has no references. Start its output with an IDR.
                image = EncodeFrame(frame, true);
            }
            return image;
        });
        if (paused) return WEBRTC_VIDEO_CODEC_OK;
        if (!image) {
            Diagnostic("hardware encode failed; fallback=software stage=" + stage_);
            return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
        }
        image->SetEncodeTime(encodeStart, TimeMillis());
        CodecSpecificInfo info{};
        info.codecType = kVideoCodecH264;
        info.codecSpecific.H264.packetization_mode = H264PacketizationMode::NonInterleaved;
        info.codecSpecific.H264.temporal_idx = kNoTemporalIdx;
        info.codecSpecific.H264.idr_frame = image->_frameType == VideoFrameType::kVideoFrameKey;
        callback_->OnEncodedImage(*image, &info);
        return WEBRTC_VIDEO_CODEC_OK;
    }
    void SetRates(const RateControlParameters& rates) override {
        if (!running_) return;
        thread_->BlockingCall([&] {
            paused_ = rates.bitrate.get_sum_bps() == 0;
            if (!paused_ && codecApi_ && bitrate_ != rates.bitrate.get_sum_bps()) {
                bitrate_ = rates.bitrate.get_sum_bps();
                if (!SetUInt(CODECAPI_AVEncCommonMeanBitRate, bitrate_)) failed_ = true;
            }
        });
    }
    EncoderInfo GetEncoderInfo() const override {
        EncoderInfo info;
        info.implementation_name = "MediaFoundation H264";
        info.is_hardware_accelerated = true;
        info.supports_native_handle = false;
        info.requested_resolution_alignment = 2;
        info.has_trusted_rate_controller = false;
        return info;
    }

private:
    bool TryNextTransform() {
        while (nextCandidate_ < candidates_->count) {
            auto* candidate = candidates_->items[nextCandidate_++];
            WCHAR* name = nullptr;
            if (SUCCEEDED(candidate->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, nullptr))) {
                const int size = WideCharToMultiByte(CP_UTF8, 0, name, -1, nullptr, 0, nullptr, nullptr);
                std::string utf8(size > 0 ? size : 1, '\0');
                if (size > 0) WideCharToMultiByte(CP_UTF8, 0, name, -1, utf8.data(), size, nullptr, nullptr);
                CoTaskMemFree(name);
                utf8.resize(utf8.size() - 1);
                Diagnostic("trying MFT=" + utf8);
            }
            if (Check(candidate->ActivateObject(IID_PPV_ARGS(&transform_)), "ActivateObject") && Configure()) {
                active_ = candidate;
                Diagnostic("hardware init successful");
                return true;
            }
            ResetTransform();
            candidate->ShutdownObject();
        }
        return false;
    }

    void ResetTransform() {
        if (transform_) {
            transform_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
            transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        }
        events_.Reset();
        codecApi_.Reset();
        transform_.Reset();
        if (active_) active_->ShutdownObject();
        active_ = nullptr;
        needInput_ = 0;
        time_ = 0;
        failed_ = false;
        headers_.clear();
    }

    void Reset() {
        ResetTransform();
        candidates_.reset();
        session_.reset();
        nextCandidate_ = 0;
        paused_ = false;
    }
    bool SetUInt(const GUID& key, ULONG value) {
        VARIANT v{};
        v.vt = VT_UI4;
        v.ulVal = value;
        return codecApi_ && Check(codecApi_->SetValue(&key, &v), "codecApi_->SetValue");
    }
    bool Configure() {
        ComPtr<IMFAttributes> attrs;
        if (!Check(transform_->GetAttributes(&attrs), "transform_->GetAttributes") ||
            !Check(attrs->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE), "attrs->SetUINT32") ||
            !Check(transform_.As(&events_), "transform_.As") || !Check(transform_.As(&codecApi_), "transform_.As")) return false;
        inputId_ = outputId_ = 0;
        DWORD inputs = 0, outputs = 0;
        if (!Check(transform_->GetStreamCount(&inputs, &outputs), "GetStreamCount") || inputs != 1 || outputs != 1)
            return false;
        const HRESULT ids = transform_->GetStreamIDs(1, &inputId_, 1, &outputId_);
        if (ids != E_NOTIMPL && !Check(ids, "GetStreamIDs")) return false;
        VARIANT lowLatency{};
        lowLatency.vt = VT_BOOL;
        lowLatency.boolVal = VARIANT_TRUE;
        if (!Check(codecApi_->SetValue(&CODECAPI_AVLowLatencyMode, &lowLatency), "codecApi_->SetValue")) return false;
        SetUInt(CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
        SetUInt(CODECAPI_AVEncMPVDefaultBPictureCount, 0);
        SetUInt(CODECAPI_AVEncMPVGOPSize, fps_ * 2);

        ComPtr<IMFMediaType> output, input;
        if (!MediaType(&output, MFVideoFormat_H264) ||
            !Check(output->SetUINT32(MF_MT_AVG_BITRATE, bitrate_), "output->SetUINT32") ||
            !Check(output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base), "output->SetUINT32") ||
            !Check(transform_->SetOutputType(outputId_, output.Get(), 0), "transform_->SetOutputType") || !MediaType(&input, MFVideoFormat_NV12) ||
            !Check(input->SetUINT32(MF_MT_DEFAULT_STRIDE, width_), "NV12 stride") ||
            !Check(transform_->SetInputType(inputId_, input.Get(), 0), "transform_->SetInputType") ||
            !Check(transform_->GetInputStreamInfo(inputId_, &inputInfo_), "GetInputStreamInfo")) return false;
        SetUInt(CODECAPI_AVEncCommonMeanBitRate, bitrate_);
        return Check(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0), "transform_->ProcessMessage") &&
               Check(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0), "transform_->ProcessMessage");
    }
    bool MediaType(IMFMediaType** out, const GUID& subtype) {
        if (!Check(Api().MFCreateMediaType(out), "MFCreateMediaType")) return false;
        auto* type = *out;
        return Check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "type->SetGUID") &&
               Check(type->SetGUID(MF_MT_SUBTYPE, subtype), "type->SetGUID") &&
               Check(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive), "type->SetUINT32") &&
               Check(MFSetAttributeSize(type, MF_MT_FRAME_SIZE, width_, height_), "MFSetAttributeSize") &&
               Check(MFSetAttributeRatio(type, MF_MT_FRAME_RATE, fps_, 1), "MFSetAttributeRatio") &&
               Check(MFSetAttributeRatio(type, MF_MT_PIXEL_ASPECT_RATIO, 1, 1), "MFSetAttributeRatio");
    }
    bool Wait(MediaEventType wanted) {
        if (wanted == METransformNeedInput && needInput_) { --needInput_; return true; }
        // Real-time mode must produce one output per input. A stuck/buffering driver falls back, never waits forever.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        while (std::chrono::steady_clock::now() < deadline) {
            ComPtr<IMFMediaEvent> event;
            HRESULT hr = events_->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
            if (hr == MF_E_NO_EVENTS_AVAILABLE) { Sleep(1); continue; }
            if (!Check(hr, "GetEvent")) return false;
            MediaEventType type;
            HRESULT status;
            if (!Check(event->GetType(&type), "event->GetType") || !Check(event->GetStatus(&status), "event->GetStatus") || !Check(status, "status") || type == MEError) return false;
            if (type == wanted) return true;
            if (type == METransformNeedInput) ++needInput_;
        }
        Diagnostic("MFT event timeout wanted=" + std::to_string(wanted));
        return false;
    }
    std::optional<EncodedImage> EncodeFrame(const VideoFrame& frame, bool key) {
        stage_ = "input validation / rate update";
        if (!transform_ || failed_ || frame.width() != width_ || frame.height() != height_) return std::nullopt;
        if (paused_) return std::nullopt;
        stage_ = "convert / wait input";
        auto i420 = frame.video_frame_buffer()->ToI420();
        if (!i420 || !Wait(METransformNeedInput)) return std::nullopt;
        if (key && !SetUInt(CODECAPI_AVEncVideoForceKeyFrame, TRUE)) return std::nullopt;
        ComPtr<IMFSample> input;
        ComPtr<IMFMediaBuffer> pixels;
        const DWORD size = width_ * height_ * 3 / 2;
        if (!Check(Api().MFCreateSample(&input), "MFCreateSample") ||
            !Check(Api().MFCreateAlignedMemoryBuffer(std::max(size, inputInfo_.cbSize),
                inputInfo_.cbAlignment ? inputInfo_.cbAlignment - 1 : 0, &pixels), "allocate aligned NV12 buffer")) return std::nullopt;
        BYTE* data = nullptr;
        if (!Check(pixels->Lock(&data, nullptr, nullptr), "pixels->Lock")) return std::nullopt;
        int converted = libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
            i420->DataV(), i420->StrideV(), data, width_, data + width_ * height_, width_, width_, height_);
        pixels->Unlock();
        const LONGLONG duration = 10000000LL / fps_;
        stage_ = "submit input / wait output";
        if (converted || !Check(pixels->SetCurrentLength(size), "pixels->SetCurrentLength") || !Check(input->AddBuffer(pixels.Get()), "input->AddBuffer") ||
            !Check(input->SetSampleTime(time_), "input->SetSampleTime") || !Check(input->SetSampleDuration(duration), "input->SetSampleDuration") ||
            !Check(transform_->ProcessInput(inputId_, input.Get(), 0), "transform_->ProcessInput") || !Wait(METransformHaveOutput)) return std::nullopt;

        stage_ = "read output";
        ComPtr<IMFSample> output = ReadOutput();
        if (!output) return std::nullopt;
        stage_ = "output timestamp / bitstream";
        LONGLONG outputTime;
        if (!Check(output->GetSampleTime(&outputTime), "output->GetSampleTime")) return std::nullopt;
        if (outputTime != time_) {
            Diagnostic("output timestamp mismatch expected=" + std::to_string(time_) + " actual=" + std::to_string(outputTime));
            return std::nullopt;
        }
        time_ += duration;
        ComPtr<IMFMediaBuffer> buffer;
        if (!Check(output->ConvertToContiguousBuffer(&buffer), "output->ConvertToContiguousBuffer")) return std::nullopt;
        DWORD length = 0;
        if (!Check(buffer->Lock(&data, nullptr, &length), "buffer->Lock")) return std::nullopt;
        std::vector<uint8_t> bytes;
        bool valid = H264AnnexB(data, length, bytes);
        buffer->Unlock();
        if (!valid) { Diagnostic("invalid H264 output bitstream"); return std::nullopt; }
        const bool idr = H264HasNal(bytes, 5);
        if (idr && (!H264HasNal(bytes, 7) || !H264HasNal(bytes, 8))) {
            ComPtr<IMFMediaType> type;
            UINT32 headerSize = 0;
            if (!Check(transform_->GetOutputCurrentType(outputId_, &type), "transform_->GetOutputCurrentType") ||
                !Check(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &headerSize), "type->GetBlobSize")) return std::nullopt;
            std::vector<uint8_t> raw(headerSize);
            if (!Check(type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, raw.data(), headerSize, nullptr), "type->GetBlob") ||
                !H264AnnexB(raw.data(), raw.size(), headers_) || !H264HasNal(headers_, 7) || !H264HasNal(headers_, 8)) return std::nullopt;
            bytes.insert(bytes.begin(), headers_.begin(), headers_.end());
        }
        if ((key && !idr) || (idr && (!H264HasConstrainedBaselineSps(bytes) || !H264HasNal(bytes, 8)))) {
            Diagnostic("H264 output rejected: keyframe/profile/SPS/PPS requirements; requestedKey=" + std::to_string(key) + " idr=" + std::to_string(idr));
            return std::nullopt;
        }
        EncodedImage image;
        image.SetEncodedData(EncodedImageBuffer::Create(bytes.data(), bytes.size()));
        image.SetRtpTimestamp(frame.rtp_timestamp());
        image._encodedWidth = width_;
        image._encodedHeight = height_;
        image.capture_time_ms_ = frame.timestamp_us() / 1000;
        image.ntp_time_ms_ = frame.ntp_time_ms();
        image.rotation_ = frame.rotation();
        image._frameType = idr ? VideoFrameType::kVideoFrameKey : VideoFrameType::kVideoFrameDelta;
        return image;
    }
    ComPtr<IMFSample> ReadOutput() {
        for (int attempt = 0; attempt < 2; ++attempt) {
            MFT_OUTPUT_STREAM_INFO stream{};
            if (!Check(transform_->GetOutputStreamInfo(outputId_, &stream), "transform_->GetOutputStreamInfo")) return {};
            ComPtr<IMFSample> supplied;
            if (!(stream.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES))) {
                ComPtr<IMFMediaBuffer> buffer;
                if (!Check(Api().MFCreateSample(&supplied), "MFCreateSample") ||
                    !Check(Api().MFCreateAlignedMemoryBuffer(std::max(stream.cbSize, DWORD(width_ * height_ * 3 / 2)),
                        stream.cbAlignment ? stream.cbAlignment - 1 : 0, &buffer), "Api") ||
                    !Check(supplied->AddBuffer(buffer.Get()), "supplied->AddBuffer")) return {};
            }
            MFT_OUTPUT_DATA_BUFFER result{};
            result.dwStreamID = outputId_;
            result.pSample = supplied.Get();
            DWORD status = 0;
            HRESULT hr = transform_->ProcessOutput(0, 1, &result, &status);
            if (result.pEvents) result.pEvents->Release();
            ComPtr<IMFSample> output;
            if (result.pSample != supplied.Get()) output.Attach(result.pSample);
            else output = supplied;
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                ComPtr<IMFMediaType> type;
                GUID subtype{};
                if (!Check(transform_->GetOutputAvailableType(outputId_, 0, &type), "transform_->GetOutputAvailableType") ||
                    !Check(type->GetGUID(MF_MT_SUBTYPE, &subtype), "type->GetGUID") || subtype != MFVideoFormat_H264 ||
                    !Check(transform_->SetOutputType(outputId_, type.Get(), 0), "transform_->SetOutputType")) return {};
                Diagnostic("MFT output format changed; waiting for the next output event");
                if (!Wait(METransformHaveOutput)) return {};
                continue;
            }
            if (!Check(hr, "ProcessOutput")) {
                Diagnostic("MFT output flags=" + std::to_string(stream.dwFlags) +
                    " sampleSupplied=" + std::to_string(supplied != nullptr) +
                    " status=" + std::to_string(result.dwStatus));
                return {};
            }
            return output;
        }
        return {};
    }

    std::unique_ptr<Thread> thread_;
    bool running_ = false;
    EncodedImageCallback* callback_ = nullptr;
    std::unique_ptr<MfSession> session_;
    std::unique_ptr<Activations> candidates_;
    UINT32 nextCandidate_ = 0;
    IMFActivate* active_ = nullptr; // owned by candidates_
    DWORD inputId_ = 0, outputId_ = 0;
    MFT_INPUT_STREAM_INFO inputInfo_{};
    ComPtr<IMFTransform> transform_;
    ComPtr<IMFMediaEventGenerator> events_;
    ComPtr<ICodecAPI> codecApi_;
    std::string stage_;
    int width_ = 0, height_ = 0;
    UINT32 fps_ = 30, bitrate_ = 0, needInput_ = 0;
    LONGLONG time_ = 0;
    bool failed_ = false, paused_ = false;
    std::vector<uint8_t> headers_;
};

class WindowsFactory final : public VideoEncoderFactory {
public:
    explicit WindowsFactory(std::unique_ptr<VideoEncoderFactory> software) : software_(std::move(software)) {}
    std::vector<SdpVideoFormat> GetSupportedFormats() const override { return software_->GetSupportedFormats(); }
    CodecSupport QueryCodecSupport(const SdpVideoFormat& format, std::optional<std::string> mode,
                                   std::optional<Resolution> resolution) const override {
        auto result = software_->QueryCodecSupport(format, mode, resolution);
        if (result.is_supported && (!mode || *mode == "L1T1") && HardwareFormat(format) && HardwareAvailable())
            result.is_power_efficient = true;
        return result;
    }
    std::unique_ptr<VideoEncoder> Create(const Environment& env, const SdpVideoFormat& format) override {
        auto software = software_->Create(env, format);
        if (!software || !HardwareFormat(format) || !HardwareAvailable()) {
            Diagnostic("create codec=" + format.name + " hardwareFormat=" + std::to_string(HardwareFormat(format)) + " implementation=software");
            return software;
        }
        Diagnostic("create codec=H264 implementation=MediaFoundation with software fallback");
        return CreateVideoEncoderSoftwareFallbackWrapper(env, std::move(software), std::make_unique<MfH264Encoder>(), false);
    }
private:
    std::unique_ptr<VideoEncoderFactory> software_;
};
}

std::string WindowsEncoderDiagnosticsJson() {
    std::lock_guard<std::mutex> lock(diagnosticMutex);
    std::string result;
    for (const auto& entry : diagnosticEvents) {
        if (!result.empty()) result += ",";
        result += "{\"id\":\"krtc-encoder-" + std::to_string(entry.first) +
            "\",\"type\":\"krtc-encoder-diagnostic\",\"timestamp\":0,\"message\":" + JsonString(entry.second) + "}";
    }
    return result;
}

std::unique_ptr<webrtc::VideoEncoderFactory> WindowsVideoEncoderFactory(
    std::unique_ptr<webrtc::VideoEncoderFactory> software) {
    return std::make_unique<WindowsFactory>(std::move(software));
}
}
