// Runs on Windows; Wine can verify the software paths without a GPU.
#include "krtc_windows_encoder.h"
#include "krtc_h264_bitstream.h"
#include "krtc_platform.h"
#include "api/environment/environment_factory.h"
#include "api/video/i420_buffer.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_open_h264_adapter.h"
#include "api/video_codecs/video_encoder_software_fallback_wrapper.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <cstdlib>

// Keep checks active while matching the release libwebrtc ABI (NDEBUG).
#undef assert
#define assert(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "Check failed: %s at %s:%d\n", #condition, __FILE__, __LINE__); \
    std::abort(); \
} } while (false)

using namespace webrtc;

class Sink final : public EncodedImageCallback {
public:
    int frames = 0;
    Result OnEncodedImage(const EncodedImage& image, const CodecSpecificInfo* info) override {
        std::vector<uint8_t> bytes;
        assert(krtc::H264AnnexB(image.data(), image.size(), bytes));
        assert(info && info->codecType == kVideoCodecH264);
        assert(image.qp_ >= 0 && image.qp_ <= 51);
        if (frames == 0) {
            assert(image._frameType == VideoFrameType::kVideoFrameKey);
            assert(krtc::H264HasNal(bytes, 5) && krtc::H264HasNal(bytes, 7) && krtc::H264HasNal(bytes, 8));
        }
        assert(image.RtpTimestamp() == 90000u + frames * 3000u);
        ++frames;
        return Result(Result::OK);
    }
    void OnFrameDropped(uint32_t, int, bool) override { assert(false); }
};

class BrokenHardware final : public VideoEncoder {
public:
    explicit BrokenHardware(bool initFails) : initFails_(initFails) {}
    int InitEncode(const VideoCodec*, const Settings&) override {
        return initFails_ ? WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE : WEBRTC_VIDEO_CODEC_OK;
    }
    int32_t RegisterEncodeCompleteCallback(EncodedImageCallback*) override { return 0; }
    int32_t Release() override { return 0; }
    int32_t Encode(const VideoFrame&, const std::vector<VideoFrameType>*) override {
        return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
    }
    void SetRates(const RateControlParameters&) override {}
    EncoderInfo GetEncoderInfo() const override {
        EncoderInfo info;
        info.implementation_name = "broken hardware";
        info.is_hardware_accelerated = true;
        return info;
    }
private:
    bool initFails_;
};

void EncodeFrames(VideoEncoder& encoder, bool requireSoftware, bool requireHardware) {
    VideoCodec codec;
    codec.codecType = kVideoCodecH264;
    codec.width = 640;
    codec.height = 360;
    codec.startBitrate = 200;
    codec.maxBitrate = 1000;
    codec.minBitrate = 30;
    codec.maxFramerate = 30;
    codec.active = true;
    *codec.H264() = VideoEncoder::GetDefaultH264Settings();
    codec.SetFrameDropEnabled(false);
    Sink sink;
    assert(encoder.RegisterEncodeCompleteCallback(&sink) == 0);
    assert(encoder.InitEncode(&codec, VideoEncoder::Settings(VideoEncoder::Capabilities(false), 4, 1200)) == 0);
    VideoBitrateAllocation bitrate;
    bitrate.SetBitrate(0, 0, 200000);
    encoder.SetRates(VideoEncoder::RateControlParameters(bitrate, 30));
    auto pixels = I420Buffer::Create(codec.width, codec.height);
    I420Buffer::SetBlack(pixels.get());
    for (int i = 0; i < 10; ++i) {
        const auto frame = VideoFrame::Builder().set_video_frame_buffer(pixels)
            .set_rtp_timestamp(90000 + i * 3000).set_timestamp_us(1000000 + i * 33333).build();
        std::vector<VideoFrameType> types{i == 0 ? VideoFrameType::kVideoFrameKey : VideoFrameType::kVideoFrameDelta};
        assert(encoder.Encode(frame, &types) == 0);
    }
    assert(sink.frames == 10);
    const auto scaling = encoder.GetEncoderInfo().scaling_settings;
    assert(scaling.thresholds && scaling.thresholds->low == 24 && scaling.thresholds->high == 37);
    const auto info = encoder.GetEncoderInfo();
    std::printf("encoder=%s hardware=%d frames=%d\n", info.implementation_name.c_str(), info.is_hardware_accelerated, sink.frames);
    if (requireSoftware) assert(!info.is_hardware_accelerated);
    if (requireHardware) assert(info.is_hardware_accelerated);
    assert(encoder.Release() == 0);
}

class AdaptedSink final : public VideoSinkInterface<VideoFrame> {
public:
    int width = 0, height = 0, count = 0;
    void OnFrame(const VideoFrame& frame) override { width = frame.width(); height = frame.height(); ++count; }
};

void CheckSourceAdaptation() {
    auto source = make_ref_counted<krtc::PushVideoSource>(true);
    VideoTrackSourceInterface* video = source.get();
    AdaptedSink sink, preview, counter;
    // Passive local consumers must not cancel the encoder's restrictions.
    video->AddOrUpdateSink(&preview, VideoSinkWants());
    video->AddOrUpdateSink(&counter, VideoSinkWants());
    VideoSinkWants wants;
    wants.is_active = true;
    wants.max_pixel_count = 320 * 180;
    wants.target_pixel_count = 320 * 180;
    wants.resolution_alignment = 2;
    video->AddOrUpdateSink(&sink, wants);
    const auto buffer = I420Buffer::Create(1280, 720);
    I420Buffer::SetBlack(buffer.get());
    source->Push(VideoFrame::Builder().set_video_frame_buffer(buffer).set_timestamp_us(1000000).build());
    assert(sink.count == 1 && sink.width * sink.height <= 320 * 180);
    wants.max_pixel_count = 1280 * 720;
    wants.target_pixel_count = 1280 * 720;
    video->AddOrUpdateSink(&sink, wants);
    source->Push(VideoFrame::Builder().set_video_frame_buffer(buffer).set_timestamp_us(1033333).build());
    assert(sink.count == 2 && sink.width == 1280 && sink.height == 720);
    // Framerate restrictions must be honored too, while normal 30/60 FPS shares retain their requested rate.
    wants.max_framerate_fps = 5;
    video->AddOrUpdateSink(&sink, wants);
    for (int i = 0; i < 30; ++i)
        source->Push(VideoFrame::Builder().set_video_frame_buffer(buffer).set_timestamp_us(2000000 + i * 33333).build());
    assert(sink.count > 2 && sink.count <= 8);
    video->RemoveSink(&sink);
    video->RemoveSink(&preview);
    video->RemoveSink(&counter);
    std::puts("Source downscale, recovery and FPS restriction checks passed");
}

int main(int argc, char** argv) {
    CheckSourceAdaptation();
    const bool requireSoftware = argc > 1 && std::strcmp(argv[1], "--require-software") == 0;
    const bool requireHardware = argc > 1 && std::strcmp(argv[1], "--require-hardware") == 0;
    auto env = CreateEnvironment();
    auto factory = krtc::WindowsVideoEncoderFactory(
        std::make_unique<VideoEncoderFactoryTemplate<OpenH264EncoderTemplateAdapter>>());
    const SdpVideoFormat format("H264", {{"profile-level-id", "42e01f"}, {"packetization-mode", "1"}, {"level-asymmetry-allowed", "1"}});
    auto encoder = factory->Create(env, format);
    assert(encoder);
    EncodeFrames(*encoder, requireSoftware, requireHardware);
    for (bool initFails : {true, false}) {
        auto software = OpenH264EncoderTemplateAdapter::CreateEncoder(env, format);
        auto fallback = CreateVideoEncoderSoftwareFallbackWrapper(env, std::move(software),
            std::make_unique<BrokenHardware>(initFails), false);
        EncodeFrames(*fallback, true, false);
    }
    const auto diagnostics = krtc::WindowsEncoderDiagnosticsJson();
    assert(diagnostics.find("hardware MFT candidates=") != std::string::npos);
    assert(diagnostics.find("krtc-encoder-diagnostic") != std::string::npos);
    assert(diagnostics == krtc::WindowsEncoderDiagnosticsJson()); // reading must not consume another PC's events
    std::printf("diagnostics=[%s]\n", diagnostics.c_str());
    std::puts("Windows H264 and software fallback checks passed");
}
