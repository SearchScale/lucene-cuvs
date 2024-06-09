package com.searchscale.lucene.vectorsearch.benchmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.codecs.lucene99.Lucene99Codec.Mode;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.searchscale.lucene.vectorsearch.CuVSCodec;
import com.searchscale.lucene.vectorsearch.CuVSKnnFloatVectorQuery;
import com.searchscale.lucene.vectorsearch.CuVSVectorsWriter.MergeStrategy;

public class LuceneVectorSearchExample {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static boolean RESULTS_DEBUGGING = false; // when enabled, titles are indexed and printed after search

  public static void main(String[] args) throws Exception {
    // [0] Parse Arguments
    BenchmarkConfiguration config = new BenchmarkConfiguration(args);
    Map<String, Object> metrics = new HashMap<String, Object>();
    List<QueryResult> queryResults = Collections.synchronizedList(new ArrayList<QueryResult>());
    config.debugPrintArguments();

    // [1] Read CSV file and parse data set
    log.info("Parsing CSV file ...");
    List<String> titles = new ArrayList<String>();
    List<float[]> vectorColumn = new ArrayList<float[]>();
    long parseStartTime = System.currentTimeMillis();
    parseCSVFile(config, titles, vectorColumn);
    System.out.println("Time taken for parsing dataset: " + (System.currentTimeMillis() - parseStartTime + " ms"));

    // [2] Benchmarking setup

    // HNSW Writer:
    IndexWriterConfig hnswWriterConfig = new IndexWriterConfig(new StandardAnalyzer()).setCodec(getHnswCodec(config));
    hnswWriterConfig.setMaxBufferedDocs(config.commitFreq);
    hnswWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

    // CuVS Writer:
    IndexWriterConfig cuvsIndexWriterConfig = new IndexWriterConfig(new StandardAnalyzer()).setCodec(new CuVSCodec(
        config.cuvsWriterThreads, config.cagraIntermediateGraphDegree, config.cagraGraphDegree, config.mergeStrategy));
    cuvsIndexWriterConfig.setMaxBufferedDocs(config.commitFreq);
    cuvsIndexWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

    if (config.mergeStrategy.equals(MergeStrategy.NO_MERGE)) {
      hnswWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
      cuvsIndexWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
    }

    IndexWriter hnswIndexWriter = new IndexWriter(new ByteBuffersDirectory(), hnswWriterConfig);
    IndexWriter cuvsIndexWriter = new IndexWriter(new ByteBuffersDirectory(), cuvsIndexWriterConfig);

    for (IndexWriter writer : new IndexWriter[] { cuvsIndexWriter, hnswIndexWriter }) {
      Codec codec = writer.getConfig().getCodec();
      String codecName = codec.getClass().getSimpleName().isEmpty() ? codec.getClass().getSuperclass().getSimpleName()
          : codec.getClass().getSimpleName();
      log.error("----------\nIndexing documents using {} ...", codecName); // error for different coloring
      long indexStartTime = System.currentTimeMillis();
      indexDocuments(writer, config, titles, vectorColumn, config.commitFreq);
      long indexTimeTaken = System.currentTimeMillis() - indexStartTime;
      if (codec instanceof CuVSCodec) {
        metrics.put("cuvs-indexing-time", indexTimeTaken);
      } else {
        metrics.put("hnsw-indexing-time", indexTimeTaken);
      }

      log.info("Time taken for index building (end to end): " + indexTimeTaken + " ms");

      log.error("Querying documents using {}...", codecName); // error for different coloring
      query(writer.getDirectory(), config, codec instanceof CuVSCodec, metrics, queryResults);
    }

    writeCSV(queryResults, "neighbors.csv");
    String resultsJson = new ObjectMapper().writerWithDefaultPrettyPrinter()
        .writeValueAsString(Map.of("configuration", config, "metrics", metrics));
    FileUtils.write(new File("benchmark_results.json"), resultsJson, Charset.forName("UTF-8"));

    log.info("\n-----\nOverall metrics: " + metrics + "\nMetrics: \n" + resultsJson + "\n-----");

  }

  private static void parseCSVFile(BenchmarkConfiguration config, List<String> titles, List<float[]> vectorColumn)
      throws IOException, CsvValidationException {
    InputStreamReader isr = null;
    ZipFile zipFile = null;
    if (config.datasetFile.endsWith(".zip")) {
      zipFile = new ZipFile(config.datasetFile);
      isr = new InputStreamReader(zipFile.getInputStream(zipFile.entries().nextElement()));
    } else if (config.datasetFile.endsWith(".gz")) {
      isr = new InputStreamReader(new GZIPInputStream(new FileInputStream(config.datasetFile)));
    } else {
      isr = new InputStreamReader(new FileInputStream(config.datasetFile));
    }

    try (CSVReader csvReader = new CSVReader(isr)) {
      String[] csvLine;
      int countOfDocuments = 0;
      while ((csvLine = csvReader.readNext()) != null) {
        if ((countOfDocuments++) == 0)
          continue; // skip the first line of the file, it is a header
        try {
          titles.add(csvLine[1]);
          vectorColumn.add(reduceDimensionVector(parseFloatArrayFromStringArray(csvLine[config.indexOfVector]),
              config.vectorDimension));
        } catch (Exception e) {
          System.out.print("#");
          countOfDocuments -= 1;
        }
        if (countOfDocuments % 1000 == 0)
          System.out.print(".");

        if (countOfDocuments == config.numDocs + 1)
          break;
      }
      System.out.println();
    }
    if (zipFile != null)
      zipFile.close();
  }

  private static class BenchmarkConfiguration {
    public String datasetFile;
    public int indexOfVector;
    public String vectorColName;
    public int numDocs;
    public int vectorDimension;
    public String queryFile;
    public int commitFreq;
    public int topK;
    public int hnswThreads;
    public int cuvsWriterThreads;
    public MergeStrategy mergeStrategy;
    public int queryThreads;

    // HNSW parameters
    public int hnswMaxConn; // 16 default (max 512)
    public int hnswBeamWidth; // 100 default (max 3200)
    public int hnswVisitedLimit;

    // Cagra parameters
    public int cagraIntermediateGraphDegree; // 128 default
    public int cagraGraphDegree; // 64 default
    public int cagraITopK;
    public int cagraSearchWidth;

    public BenchmarkConfiguration(String[] args) {
      this.datasetFile = args[0];
      this.indexOfVector = Integer.valueOf(args[1]);
      this.vectorColName = args[2];
      this.numDocs = Integer.valueOf(args[3]);
      this.vectorDimension = Integer.valueOf(args[4]);
      this.queryFile = args[5];
      this.commitFreq = Integer.valueOf(args[6]);
      this.topK = Integer.valueOf(args[7]);
      this.hnswThreads = Integer.valueOf(args[8]);
      this.cuvsWriterThreads = Integer.valueOf(args[9]);
      this.mergeStrategy = MergeStrategy.valueOf(args[10]);
      this.queryThreads = Integer.valueOf(args[11]);

      // Parameter tuning
      this.hnswMaxConn = Integer.valueOf(args[12]);
      this.hnswBeamWidth = Integer.valueOf(args[13]);
      this.hnswVisitedLimit = Integer.valueOf(args[14]);
      this.cagraIntermediateGraphDegree = Integer.valueOf(args[15]);
      this.cagraGraphDegree = Integer.valueOf(args[16]);
      this.cagraITopK = Integer.valueOf(args[17]);
      this.cagraSearchWidth = Integer.valueOf(args[18]);
    }

    private void debugPrintArguments() {
      System.out.println("Dataset file used is: " + datasetFile);
      System.out.println("Index of vector field is: " + indexOfVector);
      System.out.println("Name of the vector field is: " + vectorColName);
      System.out.println("Number of documents to be indexed are: " + numDocs);
      System.out.println("Number of dimensions are: " + vectorDimension);
      System.out.println("Query file used is: " + queryFile);
      System.out.println("Commit frequency (every n documents): " + commitFreq);
      System.out.println("TopK value is: " + topK);
      System.out.println("Lucene HNSW threads: " + hnswThreads);
      System.out.println("cuVS Merge strategy: " + mergeStrategy);
      System.out.println("Query threads: " + queryThreads);

      System.out.println("------- algo parameters ------");
      System.out.println("hnswMaxConn: " + hnswMaxConn);
      System.out.println("hnswBeamWidth: " + hnswBeamWidth);
      System.out.println("hnswVisitedLimit: " + hnswVisitedLimit);
      System.out.println("cagraIntermediateGraphDegree: " + cagraIntermediateGraphDegree);
      System.out.println("cagraGraphDegree: " + cagraGraphDegree);
      System.out.println("cagraITopK: " + cagraITopK);
      System.out.println("cagraSearchWidth: " + cagraSearchWidth);
    }
  }

  private static void indexDocuments(IndexWriter writer, BenchmarkConfiguration config, List<String> titles,
      List<float[]> vecCol, int commitFrequency) throws IOException, InterruptedException {

    int threads = writer.getConfig().getCodec() instanceof CuVSCodec ? 1 : config.hnswThreads;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger docsIndexed = new AtomicInteger(0);
    AtomicBoolean commitBeingCalled = new AtomicBoolean(false);

    for (int i = 0; i < config.numDocs - 1; i++) {
      final int index = i;
      pool.submit(() -> {
        Document document = new Document();
        document.add(new StringField("id", String.valueOf(index), Field.Store.YES));
        if (RESULTS_DEBUGGING)
          document.add(new StringField("title", titles.get(index), Field.Store.YES));
        document
            .add(new KnnFloatVectorField(config.vectorColName, vecCol.get(index), VectorSimilarityFunction.EUCLIDEAN));
        try {
          while (commitBeingCalled.get())
            ; // block until commit is over
          writer.addDocument(document);
          int docs = docsIndexed.incrementAndGet();
          // if (docs % 100 == 0) log.info("Docs added: " + docs);

          synchronized (pool) {

            if (docs % commitFrequency == 0 && !commitBeingCalled.get()) {
              log.info(docs + " Docs indexed. Commit called...");
              if (commitBeingCalled.get() == false) {
                try {
                  commitBeingCalled.set(true);
                  writer.commit();
                  commitBeingCalled.set(false);
                } catch (IOException ex) {
                  ex.printStackTrace();
                }
              }
              log.info(docs + ": Done commit!");
            }
          }

        } catch (IOException ex) {
          ex.printStackTrace();
        }
      });

    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

    writer.commit();
    writer.close();
  }

  static class QueryResult {
    @JsonProperty("codec")
    final String codec;
    @JsonProperty("query-id")
    final public int queryId;
    @JsonProperty("docs")
    final List<Integer> docs;
    @JsonProperty("scores")
    final List<Float> scores;
    @JsonProperty("latency")
    final double latencyMs;

    public QueryResult(String codec, int id, List<Integer> docs, List<Float> scores, double latencyMs) {
      this.codec = codec;
      this.queryId = id;
      this.docs = docs;
      this.scores = scores;
      this.latencyMs = latencyMs;
    }

    @Override
    public String toString() {
      try {
        return new ObjectMapper().writeValueAsString(this);
      } catch (JsonProcessingException e) {
        throw new RuntimeException("Problem with converting the result to a string", e);
      }
    }
  }

  private static void query(Directory directory, BenchmarkConfiguration config, boolean useCuVS,
      Map<String, Object> metrics, List<QueryResult> queryResults) {
    try (IndexReader indexReader = DirectoryReader.open(directory)) {
      IndexSearcher indexSearcher = new IndexSearcher(indexReader);
      List<Pair<Integer, float[]>> queries = new ArrayList<Pair<Integer, float[]>>();

      int i = 0;
      for (String line : FileUtils.readFileToString(new File(config.queryFile), "UTF-8").split("\n")) {
        float queryVector[] = reduceDimensionVector(parseFloatArrayFromStringArray(line), config.vectorDimension);
        queries.add(Pair.of(i++, queryVector));
      }

      ExecutorService pool = Executors.newFixedThreadPool(config.queryThreads);
      AtomicInteger queriesFinished = new AtomicInteger(0);
      ConcurrentHashMap<Integer, Double> queryLatencies = new ConcurrentHashMap<Integer, Double>();

      long startTime = System.currentTimeMillis();
      for (Pair<Integer, float[]> queryPair : queries) {
        final Pair<Integer, float[]> pair = queryPair;
        pool.submit(() -> {
          int queryId = pair.getLeft();
          Query query;
          if (useCuVS) {
            query = new CuVSKnnFloatVectorQuery(config.vectorColName, pair.getRight(), config.topK, config.cagraITopK,
                config.cagraSearchWidth);
          } else {
            query = new KnnFloatVectorQuery(config.vectorColName, pair.getRight(), config.topK);
          }

          TopDocs topDocs;
          long searchStartTime = System.nanoTime();
          try {
            topDocs = indexSearcher.search(query, config.topK);
          } catch (IOException e) {
            throw new RuntimeException("Problem during executing a query: ", e);
          }
          double searchTimeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStartTime);
          // log.info("End to end search took: " + searchTimeTakenMs);
          queryLatencies.put(queryId, searchTimeTakenMs);
          queriesFinished.incrementAndGet();

          ScoreDoc[] hits = topDocs.scoreDocs;
          List<Integer> neighbors = new ArrayList<>();
          List<Float> scores = new ArrayList<>();
          for (ScoreDoc hit : hits) {
            neighbors.add(hit.doc);
            scores.add(hit.score);
          }

          QueryResult result = new QueryResult(useCuVS ? "lucene_cuvs" : "lucene_hnsw", queryId,
              useCuVS ? neighbors.reversed() : neighbors, useCuVS ? scores.reversed() : scores, searchTimeTakenMs);
          queryResults.add(result);
          log.info("Result: " + result);
        });
      }

      pool.shutdown();
      pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

      long endTime = System.currentTimeMillis();

      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-query-time", (endTime - startTime));
      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-query-throughput",
          (queriesFinished.get() / ((endTime - startTime) / 1000.0)));
      double avgLatency = new ArrayList<>(queryLatencies.values()).stream().reduce(0.0, Double::sum)
          / queriesFinished.get();
      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-mean-latency", avgLatency);
    } catch (Exception e) {
      e.printStackTrace();
      log.error("Exception during querying", e);
    }
  }

  private static Lucene99Codec getHnswCodec(BenchmarkConfiguration config) {
    Lucene99Codec knnVectorsCodec = new Lucene99Codec(Mode.BEST_SPEED) {
      @Override
      public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        KnnVectorsFormat knnFormat = new Lucene99HnswVectorsFormat(config.hnswMaxConn, config.hnswBeamWidth);
        return new HighDimensionKnnVectorsFormat(knnFormat, config.vectorDimension);
      }
    };
    return knnVectorsCodec;
  }

  private static float[] parseFloatArrayFromStringArray(String str) {
    float[] titleVector = ArrayUtils.toPrimitive(
        Arrays.stream(str.replace("[", "").replace("]", "").split(", ")).map(Float::valueOf).toArray(Float[]::new));
    return titleVector;
  }

  public static float[] reduceDimensionVector(float[] vector, int dim) {
    float out[] = new float[dim];
    for (int i = 0; i < dim && i < vector.length; i++)
      out[i] = vector[i];
    return out;
  }

  private static class HighDimensionKnnVectorsFormat extends KnnVectorsFormat {
    private final KnnVectorsFormat knnFormat;
    private final int maxDimensions;

    public HighDimensionKnnVectorsFormat(KnnVectorsFormat knnFormat, int maxDimensions) {
      super(knnFormat.getName());
      this.knnFormat = knnFormat;
      this.maxDimensions = maxDimensions;
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
      return knnFormat.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
      return knnFormat.fieldsReader(state);
    }

    @Override
    public int getMaxDimensions(String fieldName) {
      return maxDimensions;
    }
  }

  private static void writeCSV(List<QueryResult> list, String filename) throws Exception {
    JsonNode jsonTree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(list));
    CsvSchema.Builder csvSchemaBuilder = CsvSchema.builder();
    JsonNode firstObject = jsonTree.elements().next();
    firstObject.fieldNames().forEachRemaining(fieldName -> {
      csvSchemaBuilder.addColumn(fieldName);
    });
    CsvSchema csvSchema = csvSchemaBuilder.build().withHeader();
    CsvMapper csvMapper = new CsvMapper();
    csvMapper.writerFor(JsonNode.class).with(csvSchema).writeValue(new File(filename), jsonTree);
  }
}
