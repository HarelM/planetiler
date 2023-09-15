package com.onthegomap.planetiler.util;

import static com.onthegomap.planetiler.worker.Worker.joinFutures;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.onthegomap.planetiler.archive.Tile;
import com.onthegomap.planetiler.archive.TileArchiveConfig;
import com.onthegomap.planetiler.archive.TileArchives;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.stats.ProgressLoggers;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.worker.WorkQueue;
import com.onthegomap.planetiler.worker.WorkerPipeline;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vector_tile.VectorTileProto;

public class TileStats {

  private static final int BATCH_SIZE = 1_000;
  private static final Logger LOGGER = LoggerFactory.getLogger(TileStats.class);

  private static final CsvMapper MAPPER = new CsvMapper();
  private static final CsvSchema SCHEMA = MAPPER
    .schemaFor(OutputRow.class)
    .withoutHeader()
    .withColumnSeparator('\t')
    .withLineSeparator("\n");
  public static final ObjectWriter WRITER = MAPPER.writer(SCHEMA);

  public TileStats() {
    //    TODO load OSM tile weights
  }

  public static Path getOutputPath(Path output) {
    return output.resolveSibling(output.getFileName() + ".layerstats.tsv.gz");
  }

  public static void main(String... args) throws IOException {
    var tileStats = new TileStats();
    var arguments = Arguments.fromArgsOrConfigFile(args);
    var config = PlanetilerConfig.from(arguments);
    var stats = Stats.inMemory();
    var inputString = arguments.getString("input", "input file");
    var input = TileArchiveConfig.from(inputString);
    var localPath = input.getLocalPath();
    var output = localPath == null ?
      arguments.file("output", "output file") :
      arguments.file("output", "output file", getOutputPath(localPath));
    var counter = new AtomicLong(0);
    var timer = stats.startStage("tilestats");
    record Batch(List<Tile> tiles, CompletableFuture<List<String>> stats) {}
    WorkQueue<Batch> writerQueue = new WorkQueue<>("tilestats_write_queue", 1_000, 1, stats);
    var pipeline = WorkerPipeline.start("tilestats", stats);
    var readBranch = pipeline
      .<Batch>fromGenerator("enumerate", next -> {
        try (
          var reader = TileArchives.newReader(input, config);
          var tiles = reader.getAllTiles();
          writerQueue
        ) {
          var writer = writerQueue.threadLocalWriter();
          List<Tile> batch = new ArrayList<>(BATCH_SIZE);
          while (tiles.hasNext()) {
            var tile = tiles.next();
            if (batch.size() >= BATCH_SIZE) {
              var result = new Batch(batch, new CompletableFuture<>());
              writer.accept(result);
              next.accept(result);
              batch = new ArrayList<>(BATCH_SIZE);
            }
            batch.add(tile);
            counter.incrementAndGet();
          }
          if (!batch.isEmpty()) {
            var result = new Batch(batch, new CompletableFuture<>());
            writer.accept(result);
            next.accept(result);
          }
        }
      })
      .addBuffer("coords", 1_000)
      .sinkTo("process", config.featureProcessThreads(), prev -> {
        byte[] zipped = null;
        byte[] unzipped;
        VectorTileProto.Tile decoded;
        List<LayerStats> layerStats = null;

        try (var updater = tileStats.threadLocalUpdater()) {
          for (var batch : prev) {
            List<String> lines = new ArrayList<>(batch.tiles.size());
            for (var tile : batch.tiles) {
              if (!Arrays.equals(zipped, tile.bytes())) {
                zipped = tile.bytes();
                unzipped = Gzip.gunzip(tile.bytes());
                decoded = VectorTileProto.Tile.parseFrom(unzipped);
                layerStats = computeTileStats(decoded);
              }
              updater.recordTile(tile.coord(), zipped.length, layerStats);
              lines.addAll(TileStats.formatOutputRows(tile.coord(), zipped.length, layerStats));
            }
            batch.stats.complete(lines);
          }
        }
      });

    var writeBranch = pipeline.readFromQueue(writerQueue)
      .sinkTo("write", 1, prev -> {
        try (var writer = newWriter(output)) {
          writer.write(headerRow());
          for (var batch : prev) {
            for (var line : batch.stats.get()) {
              writer.write(line);
            }
          }
        }
      });
    ProgressLoggers loggers = ProgressLoggers.create()
      .addRateCounter("tiles", counter)
      .newLine()
      .addPipelineStats(readBranch)
      .addPipelineStats(writeBranch)
      .newLine()
      .addProcessStats();
    loggers.awaitAndLog(joinFutures(readBranch.done(), writeBranch.done()), config.logInterval());

    timer.stop();
    if (LOGGER.isDebugEnabled()) {
      tileStats.printStats();
    }
    stats.printSummary();
  }

  public static List<String> formatOutputRows(TileCoord tileCoord, int archivedBytes, List<LayerStats> layerStats)
    throws IOException {
    int hilbert = tileCoord.hilbertEncoded();
    List<String> result = new ArrayList<>();
    for (var layer : layerStats) {
      result.add(lineToString(new OutputRow(
        tileCoord.z(),
        tileCoord.x(),
        tileCoord.y(),
        hilbert,
        archivedBytes,
        layer.layer,
        layer.layerBytes,
        layer.layerFeatures,
        layer.layerAttrBytes,
        layer.layerAttrKeys,
        layer.layerAttrValues
      )));
    }
    return result;
  }

  public static Writer newWriter(Path path) throws IOException {
    return new OutputStreamWriter(
      new FastGzipOutputStream(new BufferedOutputStream(Files.newOutputStream(path,
        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))));
  }

  public static String lineToString(OutputRow output) throws IOException {
    return WRITER.writeValueAsString(output);
  }

  public static String headerRow() {
    return String.join(
      String.valueOf(SCHEMA.getColumnSeparator()),
      SCHEMA.getColumnNames()
    ) + new String(SCHEMA.getLineSeparator());
  }

  public static List<LayerStats> computeTileStats(VectorTileProto.Tile proto) {
    if (proto == null) {
      return List.of();
    }
    List<LayerStats> result = new ArrayList<>(proto.getLayersCount());
    for (var layer : proto.getLayersList()) {
      int attrSize = 0;
      for (var key : layer.getKeysList().asByteStringList()) {
        attrSize += key.size();
      }
      for (var value : layer.getValuesList()) {
        attrSize += value.getSerializedSize();
      }
      result.add(new LayerStats(
        layer.getName(),
        layer.getFeaturesCount(),
        layer.getSerializedSize(),
        attrSize,
        layer.getKeysCount(),
        layer.getValuesCount()
      ));
    }
    result.sort(Comparator.naturalOrder());
    return result;
  }

  public void printStats() {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Tile stats:");
    }
    // TODO
    //    long sumSize = 0;
    //    long sumCount = 0;
    //    long maxMax = 0;
    //    for (int z = config.minzoom(); z <= config.maxzoom(); z++) {
    //      long totalCount = tilesByZoom[z].get();
    //      long totalSize = totalTileSizesByZoom[z].get();
    //      sumSize += totalSize;
    //      sumCount += totalCount;
    //      long maxSize = maxTileSizesByZoom[z].get();
    //      maxMax = Math.max(maxMax, maxSize);
    //      LOGGER.debug("z{} avg:{} max:{}",
    //        z,
    //        format.storage(totalCount == 0 ? 0 : (totalSize / totalCount), false),
    //        format.storage(maxSize, false));
    //    }
    //    LOGGER.debug("all avg:{} max:{}",
    //      format.storage(sumCount == 0 ? 0 : (sumSize / sumCount), false),
    //      format.storage(maxMax, false));
    //    LOGGER.debug("    # tiles: {}", format.integer(this.tilesEmitted()));
  }

  public Updater threadLocalUpdater() {
    return new Updater();
  }

  @JsonPropertyOrder({
    "z",
    "x",
    "y",
    "hilbert",
    "archived_tile_bytes",
    "layer",
    "layer_bytes",
    "layer_features",
    "layer_attr_bytes",
    "layer_attr_keys",
    "layer_attr_values"
  })
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record OutputRow(
    int z,
    int x,
    int y,
    int hilbert,
    int archivedTileBytes,
    String layer,
    int layerBytes,
    int layerFeatures,
    int layerAttrBytes,
    int layerAttrKeys,
    int layerAttrValues
  ) {}

  public record LayerStats(
    String layer,
    int layerBytes,
    int layerFeatures,
    int layerAttrBytes,
    int layerAttrKeys,
    int layerAttrValues
  ) implements Comparable<LayerStats> {

    @Override
    public int compareTo(LayerStats o) {
      return layer.compareTo(o.layer);
    }
  }

  public class Updater implements AutoCloseable {

    @Override
    public void close() {
      // TODO report to parent
    }

    public void recordTile(TileCoord coord, int archivedBytes, List<LayerStats> layerStats) {
      //      TODO
    }
  }
}
