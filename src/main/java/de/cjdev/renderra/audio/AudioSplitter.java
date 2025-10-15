package de.cjdev.renderra.audio;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class AudioSplitter {

    public static final long CHUNK_SECONDS = 5;
    public static final long CHUNK_MICROS = CHUNK_SECONDS * 1_000_000L;

//    /**
//     * @return Generated Folder with .ogg Sounds, which will exist until you delete them
//     */
    public static void splitAudioFromVideo(FFmpegFrameGrabber grabber, Path outputFolder) throws IOException, InterruptedException {
        long part = 0;
        long startMicros = -1;

        //Path tempDir = Files.createTempDirectory("renderra_audio");
        Files.createDirectories(outputFolder);

        Frame frame;
        long startTimestamp = grabber.getTimestamp();

        FFmpegFrameRecorder recorder = null;
        try {
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples != null) {
                    long timestamp = grabber.getTimestamp();

                    // Start new recorder if needed
                    if (recorder == null || (timestamp - startMicros) >= CHUNK_MICROS) {
                        if (recorder != null) {
                            recorder.flush();
                            recorder.stop();
                            recorder.release();
                        }

                        String outputFileName = "part" + String.format("%05x", part++) + ".ogg";
                        String outputPath = outputFolder.resolve(outputFileName).toString();

                        //FFmpegLogCallback.set();
                        recorder = new FFmpegFrameRecorder(outputPath, grabber.getAudioChannels());
                        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
                        recorder.setSampleRate(grabber.getSampleRate());
                        recorder.setFormat("ogg");
                        recorder.setAudioBitrate(grabber.getAudioBitrate());
                        recorder.start();

                        startMicros = timestamp;
                    }

                    recorder.record(frame);
                }
            }
        } finally {
            if (recorder != null) {
                recorder.flush();
                recorder.stop();
                recorder.release();
            }
            grabber.setTimestamp(startTimestamp);
        }
    }
}
