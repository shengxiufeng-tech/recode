package app.recode.core

object Fixtures {

    /** A typical phone recording: 4K HEVC HDR (HLG), portrait via display matrix, stereo AAC, a text subtitle, a data track. */
    val PHONE_HDR_PORTRAIT = """
    {
      "streams": [
        {
          "index": 0, "codec_name": "hevc", "codec_long_name": "H.265 / HEVC (High Efficiency Video Coding)",
          "profile": "Main 10", "codec_type": "video", "width": 3840, "height": 2160,
          "pix_fmt": "yuv420p10le", "color_range": "tv", "color_space": "bt2020nc",
          "color_transfer": "arib-std-b67", "color_primaries": "bt2020",
          "r_frame_rate": "30/1", "avg_frame_rate": "30000/1001", "duration": "12.345678", "bit_rate": "48000000",
          "disposition": { "default": 1, "attached_pic": 0 },
          "tags": { "language": "und", "handler_name": "VideoHandle" },
          "side_data_list": [ { "side_data_type": "Display Matrix", "displaymatrix": "\n00000000: 0 65536 0\n", "rotation": -90 } ]
        },
        {
          "index": 1, "codec_name": "aac", "codec_type": "audio", "profile": "LC", "sample_rate": "48000",
          "channels": 2, "channel_layout": "stereo", "duration": "12.330000", "bit_rate": "256000",
          "disposition": { "default": 1, "attached_pic": 0 }, "tags": { "language": "eng" }
        },
        {
          "index": 2, "codec_name": "mov_text", "codec_type": "subtitle", "duration": "12.000000",
          "disposition": { "default": 0 }, "tags": { "language": "chi", "title": "中文 \"简体\"" }
        },
        { "index": 3, "codec_name": "none", "codec_type": "data", "tags": { "handler_name": "MetaHandle" } }
      ],
      "format": {
        "filename": "/proc/self/fd/57", "nb_streams": 4, "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
        "format_long_name": "QuickTime / MOV", "duration": "12.345678", "size": "74123456", "bit_rate": "48033000",
        "tags": { "major_brand": "mp42", "title": "Trip!" }
      }
    }
    """.trimIndent()

    /** MKV with per-stream DURATION tags, 5.1 audio, PGS subtitles and cover art. */
    val MKV_51 = """
    {
      "streams": [
        { "index": 0, "codec_name": "h264", "codec_type": "video", "width": 1920, "height": 1080,
          "pix_fmt": "yuv420p", "avg_frame_rate": "24000/1001", "r_frame_rate": "24000/1001",
          "disposition": { "default": 1 }, "tags": { "DURATION": "00:42:10.125000000", "BPS": "9000000" } },
        { "index": 1, "codec_name": "eac3", "codec_type": "audio", "sample_rate": "48000", "channels": 6,
          "channel_layout": "5.1(side)", "disposition": { "default": 1 }, "tags": { "language": "jpn", "DURATION": "00:42:10.100000000" } },
        { "index": 2, "codec_name": "aac", "codec_type": "audio", "sample_rate": "44100", "channels": 2,
          "disposition": { "default": 0 }, "tags": { "language": "eng" } },
        { "index": 3, "codec_name": "hdmv_pgs_subtitle", "codec_type": "subtitle", "disposition": { "default": 0 } },
        { "index": 4, "codec_name": "subrip", "codec_type": "subtitle", "disposition": { "default": 0 } },
        { "index": 5, "codec_name": "mjpeg", "codec_type": "video", "width": 600, "height": 600,
          "disposition": { "default": 0, "attached_pic": 1 } }
      ],
      "format": { "format_name": "matroska,webm", "size": "3000000000", "bit_rate": "9500000" }
    }
    """.trimIndent()

    val ENCODERS_OUTPUT = """
    Encoders:
     V..... = Video
     A..... = Audio
     S..... = Subtitle
     .F.... = Frame-level multithreading
     ------
     V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
     V....D h264_mediacodec      H.264/AVC (Android MediaCodec) (codec h264)
     V....D hevc_mediacodec      H.265/HEVC (Android MediaCodec) (codec hevc)
     V....D libx265              libx265 H.265 / HEVC (codec hevc)
     A....D aac                  AAC (Advanced Audio Coding)
     A....D libopus              libopus Opus (codec opus)
     S..... mov_text             3GPP Timed Text subtitle
    """.trimIndent()

    val FILTERS_OUTPUT = """
    Filters:
      T.. = Timeline support
      .S. = Slice threading
      ..C = Command support
      A = Audio input/output
      | = Source or sink filter
     ... abench            A->A       Benchmark part of a filtergraph.
     TSC crop              V->V       Crop the input video.
     ..C zscale            V->V       Apply resizing, colorspace and bit depth conversion.
     ... tonemap           V->V       Conversion to/from different dynamic ranges.
     ... loudnorm          A->A       EBU R128 loudness normalization
    """.trimIndent()
}
