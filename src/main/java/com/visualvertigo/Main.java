package com.visualvertigo;

import org.apache.commons.cli.*;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.swscale;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.bytedeco.javacpp.avcodec.avcodec_find_decoder_by_name;
import static org.bytedeco.javacpp.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.javacpp.avutil.*;

public class Main {
    private static final String HELP_OPTION = "help";
    private static final String INPUT_OPTION = "input";
    private static final String OUTPUT_OPTION = "output";
    private static final String SW_DECODERS_OPTION = "swdecoders";
    private static final String SW_ENCODERS_OPTION = "swencoders";
    private static final String HW_DECODERS_OPTION = "hwdecoders";
    private static final String HW_ENCODERS_OPTION = "hwencoders";
    private static final String HW_ACCELERATORS_OPTION = "hwaccels";
    private static final String DECODER_OPTION = "decoder";
    private static final String ENCODER_OPTION = "encoder";
    private static final String HW_ACCELERATOR_OPTION = "hwaccel";

    private static final String[] HW_ACEELS = new String[] { "vdpau", "dxva2", "vda", "videotoolbox", "qsv", "vaapi", "cuvid" };

    public static final int PIXEL_FORMAT = AV_PIX_FMT_BGR24; // look's like this one is the fastest

    public static void main(String[] args) throws FrameGrabber.Exception, FrameRecorder.Exception {
        Options options = createCommandLineOptions();

        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;

        try {
            commandLine = parser.parse(options, args, true);
        }
        catch (ParseException e) {
            commandLine = null;
        }

        if(commandLine == null || commandLine.getOptions().length == 0 || commandLine.hasOption("help"))
            printHelp(options);
        else if(commandLine.hasOption(SW_DECODERS_OPTION))
            printCodecs(false, false);
        else if(commandLine.hasOption(SW_ENCODERS_OPTION))
            printCodecs(true, false);
        else if(commandLine.hasOption(HW_DECODERS_OPTION))
            printCodecs(false, true);
        else if(commandLine.hasOption(HW_ENCODERS_OPTION))
            printCodecs(true, true);
        else if(commandLine.hasOption(HW_ACCELERATORS_OPTION))
            printHwAccelerators();
        else if(commandLine.hasOption(INPUT_OPTION))
            transcodeVideo(
                commandLine.getOptionValue(INPUT_OPTION),
                commandLine.getOptionValue(OUTPUT_OPTION),
                commandLine.getOptionValue(HW_ACCELERATOR_OPTION),
                commandLine.getOptionValue(DECODER_OPTION),
                commandLine.getOptionValue(ENCODER_OPTION)
            );
    }

    private static void transcodeVideo(String inputPath, String outputPath, String hwDecodingAccelerator, String decoder, String encoder) throws FrameGrabber.Exception, FrameRecorder.Exception {
        if(outputPath == null) {
            File outFile = new File(inputPath);
            String inName = outFile.getName();
            int dotIndex = inName.lastIndexOf(".");
            String extension = dotIndex > 0 ? inName.substring(dotIndex) : ".mp4";
            String outName = inName.substring(0, dotIndex > 0 ? dotIndex : inName.length()) + "_out" + extension;

            outputPath = inputPath.replace(inName, outName);
        }

        System.out.println("Transcoding '" + inputPath + "' to '" + outputPath + "'...");
        System.out.println();

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath);

        if(hwDecodingAccelerator != null)
            grabber.setOption("hwaccel", hwDecodingAccelerator);

        if(decoder != null)
            grabber.setVideoCodecName(decoder);

        grabber.setVideoOption("threads", "auto");
        grabber.setPixelFormat(PIXEL_FORMAT);
        grabber.start();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, grabber.getImageWidth(), grabber.getImageHeight());
        recorder.setFormat(grabber.getFormat());
        recorder.setFrameRate(grabber.getFrameRate());
        recorder.setVideoBitrate(grabber.getVideoBitrate());
        recorder.setVideoOption("threads", "auto");

        if(encoder != null) {
            avcodec.AVCodec enc = avcodec_find_encoder_by_name(encoder);

            IntPointer formats = enc.pix_fmts();
            int selectedFormat = avcodec.avcodec_find_best_pix_fmt_of_list(formats, PIXEL_FORMAT, 0, null);

            recorder.setPixelFormat(selectedFormat);
            recorder.setVideoCodecName(encoder);
        }

        recorder.start();

        int frameCount = 0;
        long startTime = System.currentTimeMillis();

        Frame inFrame;
        while ((inFrame = grabber.grabFrame(false, true, true, false)) != null) {
            BufferedImage image = createBufferedImage(inFrame); // faster conversion

            /* process image */

            Frame outFrame = createFrame(image); // faster conversion
            recorder.record(outFrame, PIXEL_FORMAT);

            ++frameCount;
        }

        float secs = (System.currentTimeMillis() - startTime) / 1000.0f;

        System.out.println();
        System.out.println("Done. Processing time: " + (Math.round(secs * 1000) / 1000.0f) + " (" + (Math.round(frameCount / secs * 1000) / 1000.0f) + " fps)");

        recorder.stop();
        grabber.stop();
    }

    private static byte[] framePixels = null;
    public static BufferedImage createBufferedImage(Frame frame) {
        ByteBuffer buffer = (ByteBuffer) frame.image[0].position(0);

        if(framePixels == null)
            framePixels = new byte[buffer.limit()];

        buffer.get(framePixels);

        ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);

        ColorModel cm = new ComponentColorModel(cs, false,false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster wr = Raster.createWritableRaster(new ComponentSampleModel(DataBuffer.TYPE_BYTE, frame.imageWidth, frame.imageHeight, frame.imageChannels, frame.imageStride, new int[] {2, 1, 0}), null);
        byte[] bufferPixels = ((DataBufferByte) wr.getDataBuffer()).getData();

        System.arraycopy(framePixels, 0, bufferPixels, 0, framePixels.length);

        return new BufferedImage(cm, wr, false, null);
    }

    private static byte[] imagePixels = null;
    public static Frame createFrame(BufferedImage image) {
        DataBufferByte imageBuffer = (DataBufferByte) image.getRaster().getDataBuffer();

        if(imagePixels == null)
            imagePixels = new byte[imageBuffer.getData().length];

        System.arraycopy(imageBuffer.getData(), 0, imagePixels, 0, imagePixels.length);

        Frame frame = new Frame(image.getWidth(), image.getHeight(), 8, 3);
        ByteBuffer frameBuffer = (ByteBuffer) frame.image[0].position(0);

        frameBuffer.put(imagePixels);

        return frame;
    }

    private static void printHwAccelerators() {
        avcodec.AVCodec codec = null;

        List<String> toTest = Arrays.asList(HW_ACEELS);
        List<String> available = new ArrayList<>();

        while(! toTest.isEmpty() && (codec = avcodec.av_codec_next(codec)) != null) {
            boolean encoder = avcodec.av_codec_is_encoder(codec) != 0;

            if(encoder)
                continue;

            String name = codec.name().getString();

            for(String hwAcceleratorName : toTest) {
                if(! name.contains(hwAcceleratorName))
                    continue;

                toTest.remove(hwAcceleratorName);
                available.add(hwAcceleratorName);
                break;
            }
        }

        if(available.isEmpty()) {
            System.out.println("(none)");
        }
        else {
            for(String name : available)
                System.out.println(name);
        }
    }

    private static void printCodecs(boolean encoders, boolean hardware) {
        avcodec.AVCodec codec = null;

        boolean printed = false;

        while((codec = avcodec.av_codec_next(codec)) != null) {
            boolean encoder = avcodec.av_codec_is_encoder(codec) != 0;

            if(encoder && ! encoders || ! encoder && encoders)
                continue;

            String name = codec.name().getString();
            boolean hw = isHwAccelerated(name);

            if(hw && ! hardware || ! hw && hardware)
                continue;

            System.out.println(name);
            printed = true;
        }

        if(! printed)
            System.out.println("(none)");
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("javacv_cli", options);
    }

    private static Options createCommandLineOptions() {
        return new Options()
            .addOption(
                Option.builder(HELP_OPTION)
                    .desc("prints this message")
                    .build()
            )
            .addOption(
                Option.builder(INPUT_OPTION)
                    .desc("input file path")
                    .hasArg()
                    .argName("file_path")
                    .build()
            )
            .addOption(
                Option.builder(OUTPUT_OPTION)
                    .desc("output file")
                    .hasArg()
                    .argName("file_path")
                    .build()
            )
            .addOption(
                Option.builder(SW_DECODERS_OPTION)
                    .desc("lists all registered software decoders")
                    .build()
            )
            .addOption(
                Option.builder(SW_ENCODERS_OPTION)
                    .desc("lists all registered software encoders")
                    .build()
            )
            .addOption(
                Option.builder(HW_DECODERS_OPTION)
                    .desc("lists all registered hardware decoders")
                    .build()
            )
            .addOption(
                Option.builder(HW_ENCODERS_OPTION)
                    .desc("lists all registered hardware encoders")
                    .build()
            )
            .addOption(
                Option.builder(HW_ACCELERATORS_OPTION)
                    .desc("lists hardware acceleration methods available for decoding")
                    .build()
            )
            .addOption(
                Option.builder(DECODER_OPTION)
                    .desc("use selected decoder")
                    .hasArg()
                    .argName("decoder_name")
                    .build()
            )
            .addOption(
                Option.builder(ENCODER_OPTION)
                    .desc("use selected encoder")
                    .hasArg()
                    .argName("encoder_name")
                    .build()
            )
            .addOption(
                Option.builder(HW_ACCELERATOR_OPTION)
                    .desc("use selected hardware decoding method")
                    .hasArg()
                    .argName("hwaccel_name")
                    .build()
            );
    }

    private static boolean isHwAccelerated(String codecName) {
        for(String hwAcceleratorName : HW_ACEELS) {
            if(! codecName.contains(hwAcceleratorName))
                continue;

            return true;
        }

        return false;
    }
}
