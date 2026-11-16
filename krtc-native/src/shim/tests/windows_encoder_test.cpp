// Runs on Windows; Wine can verify the software paths without a GPU.
#include "krtc_windows_encoder.h"
#include "krtc_h264_bitstream.h"
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

using namespace webrtc;

class Sink final : public EncodedImageCallback {
public:
    int frames = 0;
    Result OnEncodedImage(const EncodedImage& image, const CodecSpecificInfo* info) override {
        std::vector<uint8_t> bytes;
        assert(krtc::H264AnnexB(image.data(), image.size(), bytes));
        assert(info && info->codecType == kVideoCodecH264);
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
    const auto info = encoder.GetEncoderInfo();
    std::printf("encoder=%s hardware=%d frames=%d\n", info.implementation_name.c_str(), info.is_hardware_accelerated, sink.frames);
    if (requireSoftware) assert(!info.is_hardware_accelerated);
    if (requireHardware) assert(info.is_hardware_accelerated);
    assert(encoder.Release() == 0);
}

int main(int argc, char** argv) {
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
    std::puts("Windows H264 and software fallback checks passed");
}
