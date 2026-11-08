package androidx.media3.extractor.mp4;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.extractor.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises actual compiled player classes; no Android rendering or hardware claims. */
public final class CoreBehaviorChecks {
  private static int checks;
  private static void check(boolean condition, String label) {
    checks++;
    if (!condition) throw new AssertionError(label);
  }
  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
  }
  private static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
  }
  private static Object call(Object target, String name, Object... args) throws Exception {
    for (Method m : target.getClass().getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == args.length) {
        m.setAccessible(true);
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw new AssertionError(name, e.getCause()); }
      }
    }
    throw new NoSuchMethodException(name);
  }
  private static Format dv(String codecs) {
    return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build();
  }
  private static void decoders() throws Exception {
    Format dv7 = dv("dvhe.07.06");
    check(MediaCodecUtil.getAlternativeCodecMimeType(dv7) == null, "old overload defaults off");
    check(MediaCodecUtil.getAlternativeCodecMimeType(dv7, false) == null, "DV7 off");
    check(MimeTypes.VIDEO_H265.equals(MediaCodecUtil.getAlternativeCodecMimeType(dv7, true)), "DV7 on");
    check(MediaCodecUtil.getAlternativeCodecMimeType(dv("dvhe.05.06"), true) == null, "DV5 never mapped");
    check(MimeTypes.VIDEO_H265.equals(MediaCodecUtil.getAlternativeCodecMimeType(dv("dvhe.08.06"), false)), "DV8.1 unchanged");
    check(MimeTypes.VIDEO_H264.equals(MediaCodecUtil.getAlternativeCodecMimeType(dv("dvav.09.06"), false)), "DV9 unchanged");
    check(MimeTypes.VIDEO_AV1.equals(MediaCodecUtil.getAlternativeCodecMimeType(dv("dav1.10.06"), false)), "DV10 unchanged");
    check(MediaCodecUtil.getAlternativeCodecMimeType(dv("bad"), true) == null, "bad codecs safe");
    List<String> queries = new ArrayList<>();
    MediaCodecSelector selector = (mime, secure, tunneling) -> {
      queries.add(mime + ":" + secure + ":" + tunneling); return Collections.emptyList();
    };
    MediaCodecUtil.getAlternativeDecoderInfos(selector, dv7, true, true, true);
    check(queries.equals(Collections.singletonList("video/hevc:true:true")), "secure and tunnel retained");
    queries.clear();
    MediaCodecUtil.getAlternativeDecoderInfos(selector, dv7, true, false);
    check(queries.isEmpty(), "old selector overload defaults off");
    Method lookup = MediaCodecVideoRenderer.class.getDeclaredMethod("getDecoderInfos", android.content.Context.class,
        MediaCodecSelector.class, Format.class, boolean.class, boolean.class, boolean.class);
    lookup.setAccessible(true);
    lookup.invoke(null, null, selector, dv7, true, false, true);
    // Existing reference stores/carries this flag but does not apply it in final lookup.
    check(queries.equals(Collections.singletonList("video/dolby-vision:true:false")), "historical renderer route preserved");
    check(DefaultRenderersFactory.class.getMethod("setMapDV7ToHevc", boolean.class).getReturnType() == DefaultRenderersFactory.class, "factory API retained");
    check(MediaCodecVideoRenderer.Builder.class.getMethod("setMapDV7ToHevc", boolean.class).getReturnType() == MediaCodecVideoRenderer.Builder.class, "builder API retained");
  }

  private static final class Output implements TrackOutput {
    final List<byte[]> data = new ArrayList<>();
    final List<Long> times = new ArrayList<>();
    public void format(Format format) {}
    public int sampleData(androidx.media3.common.DataReader input, int length, boolean allowEnd, int part) { throw new AssertionError("unexpected streamed sample"); }
    public void sampleData(ParsableByteArray input, int length, int part) {
      byte[] bytes = new byte[length]; input.readBytes(bytes, 0, length); data.add(bytes);
    }
    public void sampleMetadata(long time, int flags, int size, int offset, CryptoData crypto) { times.add(time); }
  }
  private static TrackSampleTable table(int id, int type, String mime, long[] offsets, int[] sizes) {
    Track track = new Track(id, type, 1_000_000, 1_000_000, 100_000_000, 100_000_000,
        new Format.Builder().setSampleMimeType(mime).build(), 0, null, 0, null, null);
    long[] times = new long[offsets.length]; int[] flags = new int[offsets.length];
    for (int i = 0; i < times.length; i++) { times[i] = i * 1_000_000L; flags[i] = C.BUFFER_FLAG_KEY_FRAME; }
    return new TrackSampleTable(track, offsets, sizes, Arrays.stream(sizes).max().orElse(0), times, flags, 100_000_000);
  }
  private static Mp4Extractor extractor(TrackSampleTable... tables) throws Exception {
    Mp4Extractor e = new Mp4Extractor();
    Class<?> track = Class.forName("androidx.media3.extractor.mp4.Mp4Extractor$Mp4Track");
    Constructor<?> ctor = track.getDeclaredConstructors()[0]; ctor.setAccessible(true);
    Object tracks = Array.newInstance(track, tables.length);
    for (int i = 0; i < tables.length; i++) Array.set(tracks, i, ctor.newInstance(tables[i].track, tables[i], new Output()));
    set(e, "tracks", tracks); set(e, "firstVideoTrackIndex", 0); set(e, "parserState", 2);
    set(e, "accumulatedSampleSizes", call(e, "calculateAccumulatedSampleSizes", tracks));
    return e;
  }
  private static TrackSampleTable video() { return table(0, C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264, new long[]{100, 200}, new int[]{4, 4}); }
  private static DefaultExtractorInput input(byte[] bytes, int position) {
    int[] cursor = {position};
    return new DefaultExtractorInput((buffer, offset, length) -> {
      if (cursor[0] == bytes.length) return -1;
      int count = Math.min(length, bytes.length - cursor[0]);
      System.arraycopy(bytes, cursor[0], buffer, offset, count); cursor[0] += count; return count;
    }, position, bytes.length);
  }
  private static void prefetch() throws Exception {
    int far = 3 * 1024 * 1024;
    Mp4Extractor e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G,
        new long[]{far, far + 6}, new int[]{6, 6}));
    call(e, "prepareOutOfBandTextPrefetch");
    check(e.isOutOfBandTextPrefetchPending() && e.outOfBandTextSampleCountForTests() == 2, "two remote cues planned");
    byte[] bytes = new byte[far + 12];
    for (int i = 0; i < 12; i++) bytes[far + i] = (byte)(i + 1);
    PositionHolder seek = new PositionHolder();
    check((int)call(e, "readOutOfBandTextPrefetch", input(bytes, 0), seek) == Extractor.RESULT_SEEK && seek.position == far, "remote cue seek");
    check((int)call(e, "readOutOfBandTextPrefetch", input(bytes, far), seek) == Extractor.RESULT_CONTINUE, "read owned cue bytes");
    check(!e.isOutOfBandTextPrefetchPending(), "prefetch completed");
    Object text = Array.get(field(e, "tracks"), 1); Output output = (Output)field(text, "trackOutput");
    set(e, "sampleTrackIndex", 1);
    check((int)call(e, "readSample", input(bytes, 0), seek) == Extractor.RESULT_CONTINUE, "cached cue without stream read");
    check(output.times.equals(Collections.singletonList(0L)) && Arrays.equals(output.data.get(0), Arrays.copyOfRange(bytes, far, far + 6)), "cue data and timestamp exact");
    e.seek(100, 0);
    check(field(e, "prefetchedTextSamples") != null, "completed cache retained on seek");
    set(e, "sampleTrackIndex", 1); call(e, "readSample", input(bytes, 0), seek);
    check(output.times.equals(Arrays.asList(0L, 0L)), "cue restored after seek");
    e.seek(0, 0); check(field(e, "prefetchedTextSamples") == null, "restart invalidates cache");

    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G,
        new long[]{far, far + 6}, new int[]{6, 6}));
    call(e, "prepareOutOfBandTextPrefetch"); e.seek(100, 0);
    check(!e.isOutOfBandTextPrefetchPending() && field(e, "prefetchedTextSamples") == null, "partial prefetch discarded on seek");

    long[] offsets = new long[5]; int[] sizes = new int[5];
    for (int i = 0; i < 5; i++) { offsets[i] = far + i * 1_048_576L; sizes[i] = 1_048_576; }
    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, offsets, sizes));
    call(e, "prepareOutOfBandTextPrefetch"); check(e.outOfBandTextSampleCountForTests() == 4, "4 MiB total cap");
    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, new long[]{far}, new int[]{1_048_577}));
    call(e, "prepareOutOfBandTextPrefetch"); check(e.outOfBandTextSampleCountForTests() == 0, "1 MiB per cue cap");
    offsets = new long[33]; sizes = new int[33]; Arrays.fill(sizes, 3);
    for (int i = 0; i < 33; i++) offsets[i] = far + i * 262_144L + i * 3;
    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, offsets, sizes));
    call(e, "prepareOutOfBandTextPrefetch"); check(!e.isOutOfBandTextPrefetchPending(), "33 runs rejected");
    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, new long[]{far, far + 6, far * 2L, far * 2L + 6}, new int[]{6, 6, 6, 6}));
    call(e, "prepareOutOfBandTextPrefetch"); List<?> planned = (List<?>)field(e, "textPrefetchSamples");
    check((long)field(planned.get(0), "offset") == far * 2L && (long)field(planned.get(1), "offset") == far * 2L + 6 && (long)field(planned.get(2), "offset") == far, "runs descending, samples within run ascending");
    e = extractor(video(), table(1, C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, new long[]{far, far + 10}, new int[]{2, 6}));
    Object textTrack = Array.get(field(e, "tracks"), 1);
    check((int)call(e, "skipEmptyOutOfBandTx3gSamples", textTrack, 1, 0L) == 1, "empty distant tx3g skipped");
    set(textTrack, "sampleIndex", 0);
    check((int)call(e, "skipEmptyOutOfBandTx3gSamples", textTrack, 1, (long)far) == 0, "nearby empty tx3g preserved");
  }
  public static void main(String[] args) throws Exception {
    androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
    decoders(); prefetch();
    System.out.println("PASS " + checks + " decoder/subtitle assertions");
  }
}
