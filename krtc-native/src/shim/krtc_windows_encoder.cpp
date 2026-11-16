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

namespace krtc {
namespace {
using Microsoft::WRL::ComPtr;
using namespace webrtc;

// Dynamic loading also keeps the complete krtc DLL usable on Windows N without Media Feature Pack.
struct MfApi {
    HMODULE dll = LoadLibraryExW(L"mfplat.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
#define MF_FUNCTION(name) decltype(&name) name = dll ? reinterpret_cast<decltype(&::name)>(GetProcAddress(dll, #name)) : nullptr
    MF_FUNCTION(MFStartup);
    MF_FUNCTION(MFShutdown);
    MF_FUNCTION(MFTEnumEx);
    MF_FUNCTION(MFCreateMediaType);
    MF_FUNCTION(MFCreateSample);
    MF_FUNCTION(MFCreateMemoryBuffer);
    MF_FUNCTION(MFCreateAlignedMemoryBuffer);
#undef MF_FUNCTION
    bool available() const {
        return MFStartup && MFShutdown && MFTEnumEx && MFCreateMediaType && MFCreateSample &&
               MFCreateMemoryBuffer && MFCreateAlignedMemoryBuffer;
    }
    ~MfApi() { if (dll) FreeLibrary(dll); }
};
MfApi& Api() { static MfApi api; return api; }

struct MfSession {
    HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool started = SUCCEEDED(com) && Api().available() && SUCCEEDED(Api().MFStartup(MF_VERSION, MFSTARTUP_FULL));
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
        if (enabled) Api().MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER,
            MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, &input, &output, &items, &count);
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
            codec->numberOfSimulcastStreams > 1) return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
        const bool ok = thread_->BlockingCall([&] {
            Reset();
            width_ = codec->width;
            height_ = codec->height;
            fps_ = std::max(1u, codec->maxFramerate);
            bitrate_ = std::max(1u, codec->startBitrate) * 1000u;
            session_ = std::make_unique<MfSession>();
            Activations list(session_->started);
            for (UINT32 i = 0; i < list.count; ++i) {
                if (SUCCEEDED(list.items[i]->ActivateObject(IID_PPV_ARGS(&transform_))) && Configure()) return true;
                events_.Reset();
                codecApi_.Reset();
                transform_.Reset();
                list.items[i]->ShutdownObject();
            }
            Reset();
            return false;
        });
        if (!ok) std::fprintf(stderr, "krtc: Media Foundation H264 unavailable for this stream; using software\n");
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
            return paused ? std::optional<EncodedImage>{} : EncodeFrame(frame, key);
        });
        if (paused) return WEBRTC_VIDEO_CODEC_OK;
        if (!image) {
            std::fprintf(stderr, "krtc: Media Foundation H264 encode failed; switching to software\n");
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
    void Reset() {
        if (transform_) transform_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
        if (transform_) {
            ComPtr<IMFShutdown> shutdown;
            if (SUCCEEDED(transform_.As(&shutdown))) shutdown->Shutdown();
        }
        events_.Reset();
        codecApi_.Reset();
        transform_.Reset();
        session_.reset();
        needInput_ = 0;
        time_ = 0;
        failed_ = false;
        paused_ = false;
        headers_.clear();
    }
    bool SetUInt(const GUID& key, ULONG value) {
        VARIANT v{};
        v.vt = VT_UI4;
        v.ulVal = value;
        return codecApi_ && SUCCEEDED(codecApi_->SetValue(&key, &v));
    }
    bool Configure() {
        ComPtr<IMFAttributes> attrs;
        if (FAILED(transform_->GetAttributes(&attrs)) ||
            FAILED(attrs->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE)) ||
            FAILED(transform_.As(&events_)) || FAILED(transform_.As(&codecApi_))) return false;
        VARIANT lowLatency{};
        lowLatency.vt = VT_BOOL;
        lowLatency.boolVal = VARIANT_TRUE;
        if (FAILED(codecApi_->SetValue(&CODECAPI_AVLowLatencyMode, &lowLatency))) return false;
        SetUInt(CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
        SetUInt(CODECAPI_AVEncMPVDefaultBPictureCount, 0);
        SetUInt(CODECAPI_AVEncMPVGOPSize, fps_ * 2);

        ComPtr<IMFMediaType> output, input;
        if (!MediaType(&output, MFVideoFormat_H264) ||
            FAILED(output->SetUINT32(MF_MT_AVG_BITRATE, bitrate_)) ||
            FAILED(output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base)) ||
            FAILED(transform_->SetOutputType(0, output.Get(), 0)) || !MediaType(&input, MFVideoFormat_NV12) ||
            FAILED(transform_->SetInputType(0, input.Get(), 0))) return false;
        SetUInt(CODECAPI_AVEncCommonMeanBitRate, bitrate_);
        return SUCCEEDED(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)) &&
               SUCCEEDED(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0));
    }
    bool MediaType(IMFMediaType** out, const GUID& subtype) {
        if (FAILED(Api().MFCreateMediaType(out))) return false;
        auto* type = *out;
        return SUCCEEDED(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) &&
               SUCCEEDED(type->SetGUID(MF_MT_SUBTYPE, subtype)) &&
               SUCCEEDED(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive)) &&
               SUCCEEDED(MFSetAttributeSize(type, MF_MT_FRAME_SIZE, width_, height_)) &&
               SUCCEEDED(MFSetAttributeRatio(type, MF_MT_FRAME_RATE, fps_, 1)) &&
               SUCCEEDED(MFSetAttributeRatio(type, MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
    }
    bool Wait(MediaEventType wanted) {
        if (wanted == METransformNeedInput && needInput_) { --needInput_; return true; }
        // Real-time mode must produce one output per input. A stuck/buffering driver falls back, never waits forever.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        while (std::chrono::steady_clock::now() < deadline) {
            ComPtr<IMFMediaEvent> event;
            HRESULT hr = events_->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
            if (hr == MF_E_NO_EVENTS_AVAILABLE) { Sleep(1); continue; }
            if (FAILED(hr)) return false;
            MediaEventType type;
            HRESULT status;
            if (FAILED(event->GetType(&type)) || FAILED(event->GetStatus(&status)) || FAILED(status) || type == MEError) return false;
            if (type == wanted) return true;
            if (type == METransformNeedInput) ++needInput_;
        }
        return false;
    }
    std::optional<EncodedImage> EncodeFrame(const VideoFrame& frame, bool key) {
        if (!transform_ || failed_ || frame.width() != width_ || frame.height() != height_) return std::nullopt;
        if (paused_) return std::nullopt;
        auto i420 = frame.video_frame_buffer()->ToI420();
        if (!i420 || !Wait(METransformNeedInput)) return std::nullopt;
        if (key && !SetUInt(CODECAPI_AVEncVideoForceKeyFrame, TRUE)) return std::nullopt;
        ComPtr<IMFSample> input;
        ComPtr<IMFMediaBuffer> pixels;
        const DWORD size = width_ * height_ * 3 / 2;
        if (FAILED(Api().MFCreateSample(&input)) || FAILED(Api().MFCreateMemoryBuffer(size, &pixels))) return std::nullopt;
        BYTE* data = nullptr;
        if (FAILED(pixels->Lock(&data, nullptr, nullptr))) return std::nullopt;
        int converted = libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
            i420->DataV(), i420->StrideV(), data, width_, data + width_ * height_, width_, width_, height_);
        pixels->Unlock();
        const LONGLONG duration = 10000000LL / fps_;
        if (converted || FAILED(pixels->SetCurrentLength(size)) || FAILED(input->AddBuffer(pixels.Get())) ||
            FAILED(input->SetSampleTime(time_)) || FAILED(input->SetSampleDuration(duration)) ||
            FAILED(transform_->ProcessInput(0, input.Get(), 0)) || !Wait(METransformHaveOutput)) return std::nullopt;

        ComPtr<IMFSample> output = ReadOutput();
        if (!output) return std::nullopt;
        LONGLONG outputTime;
        if (FAILED(output->GetSampleTime(&outputTime)) || outputTime != time_) return std::nullopt;
        time_ += duration;
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(output->ConvertToContiguousBuffer(&buffer))) return std::nullopt;
        DWORD length = 0;
        if (FAILED(buffer->Lock(&data, nullptr, &length))) return std::nullopt;
        std::vector<uint8_t> bytes;
        bool valid = H264AnnexB(data, length, bytes);
        buffer->Unlock();
        if (!valid) return std::nullopt;
        const bool idr = H264HasNal(bytes, 5);
        if (idr && (!H264HasNal(bytes, 7) || !H264HasNal(bytes, 8))) {
            ComPtr<IMFMediaType> type;
            UINT32 headerSize = 0;
            if (FAILED(transform_->GetOutputCurrentType(0, &type)) ||
                FAILED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &headerSize))) return std::nullopt;
            std::vector<uint8_t> raw(headerSize);
            if (FAILED(type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, raw.data(), headerSize, nullptr)) ||
                !H264AnnexB(raw.data(), raw.size(), headers_) || !H264HasNal(headers_, 7) || !H264HasNal(headers_, 8)) return std::nullopt;
            bytes.insert(bytes.begin(), headers_.begin(), headers_.end());
        }
        if ((key && !idr) || (idr && (!H264HasConstrainedBaselineSps(bytes) || !H264HasNal(bytes, 8)))) return std::nullopt;
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
            if (FAILED(transform_->GetOutputStreamInfo(0, &stream))) return {};
            ComPtr<IMFSample> supplied;
            if (!(stream.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
                ComPtr<IMFMediaBuffer> buffer;
                if (FAILED(Api().MFCreateSample(&supplied)) ||
                    FAILED(Api().MFCreateAlignedMemoryBuffer(std::max(stream.cbSize, DWORD(width_ * height_ * 3 / 2)),
                        stream.cbAlignment ? stream.cbAlignment - 1 : 0, &buffer)) ||
                    FAILED(supplied->AddBuffer(buffer.Get()))) return {};
            }
            MFT_OUTPUT_DATA_BUFFER result{};
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
                if (FAILED(transform_->GetOutputAvailableType(0, 0, &type)) ||
                    FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) || subtype != MFVideoFormat_H264 ||
                    FAILED(transform_->SetOutputType(0, type.Get(), 0))) return {};
                continue;
            }
            return SUCCEEDED(hr) ? output : ComPtr<IMFSample>{};
        }
        return {};
    }

    std::unique_ptr<Thread> thread_;
    bool running_ = false;
    EncodedImageCallback* callback_ = nullptr;
    std::unique_ptr<MfSession> session_;
    ComPtr<IMFTransform> transform_;
    ComPtr<IMFMediaEventGenerator> events_;
    ComPtr<ICodecAPI> codecApi_;
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
        if (!software || !HardwareFormat(format) || !HardwareAvailable()) return software;
        return CreateVideoEncoderSoftwareFallbackWrapper(env, std::move(software), std::make_unique<MfH264Encoder>(), false);
    }
private:
    std::unique_ptr<VideoEncoderFactory> software_;
};
}

std::unique_ptr<webrtc::VideoEncoderFactory> WindowsVideoEncoderFactory(
    std::unique_ptr<webrtc::VideoEncoderFactory> software) {
    return std::make_unique<WindowsFactory>(std::move(software));
}
}
