package com.searchscale.lucene.vectorsearch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
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
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
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

import com.opencsv.CSVReader;

public class LuceneVectorSearchExample {

  public static String vectorColName = null;

  public static void main(String[] args) throws Exception {

    // [0] Parse Args

    String datasetFile = args[0];
    int indexOfVector = Integer.valueOf(args[1]);
    vectorColName = args[2];
    int numDocs = Integer.valueOf(args[3]);
    int dims = Integer.valueOf(args[4]);
    String queryFile = args[5];
    System.out.println("Dataset file used is: " + datasetFile);
    System.out.println("Index of vector field is: " + indexOfVector);
    System.out.println("Name of the vector field is: " + vectorColName);
    System.out.println("Number of documents to be indexed are: " + numDocs);
    System.out.println("Number of dimensions are: " + dims);
    System.out.println("Query file used is: " + queryFile);

    // [1] Setup the index
    Directory index = new ByteBuffersDirectory();
    Lucene99Codec knnVectorsCodec = getCodec(dims);
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer()).setCodec(knnVectorsCodec);

    // [2] Index
    long startTime = System.currentTimeMillis();
    {
      InputStreamReader isr = null;
      IndexWriter writer = new IndexWriter(index, config);
      if (datasetFile.endsWith(".zip")) {
        ZipFile zip = new ZipFile(datasetFile);
        isr = new InputStreamReader(zip.getInputStream(zip.entries().nextElement()));
      } else {
        isr = new InputStreamReader(new FileInputStream(datasetFile));
      }

      CSVReader reader = new CSVReader(isr);
      String[] line;
      int count = 0;
      while ((line = reader.readNext()) != null) {
        if ((count++) == 0)
          continue; // skip the first line of the file, it is a header
        Document doc = new Document();
        doc.add(new StringField("id", "" + (count - 2), Field.Store.YES));
        doc.add(new StringField("url", line[1], Field.Store.YES));
        doc.add(new StringField("title", line[2], Field.Store.YES));
        doc.add(new TextField("text", line[3], Field.Store.YES));
        float[] contentVector = reduceDimensionVector(parseFloatArrayFromStringArray(line[5]), dims);
        doc.add(new KnnFloatVectorField(vectorColName, contentVector, VectorSimilarityFunction.EUCLIDEAN));
        doc.add(new StringField("vector_id", line[6], Field.Store.YES));

        if (count % 500 == 0)
          writer.commit();
        if (count % 5000 == 0)
          System.out.println(count + " docs indexed ...");
        writer.addDocument(doc);
        if (count == numDocs)
          break;
      }
      writer.commit();
    }

    System.out.println("Time taken for index building (end to end): " + (System.currentTimeMillis() - startTime));

    // [3] Query
    try (IndexReader reader = DirectoryReader.open(index)) {
      IndexSearcher searcher = new CuVSIndexSearcher(reader);
      for (String line : FileUtils.readFileToString(new File(queryFile), "UTF-8").split("\n")) {
        float queryVector[] = reduceDimensionVector(parseFloatArrayFromStringArray(line), dims);
        Query query = new KnnFloatVectorQuery(vectorColName, queryVector, 5);
        startTime = System.currentTimeMillis();
        TopDocs topDocs = searcher.search(query, ((KnnFloatVectorQuery) query).getK());
        System.out.println("Time taken for searching (end to end): " + (System.currentTimeMillis() - startTime));
        ScoreDoc[] hits = topDocs.scoreDocs;
        System.out.println("Found " + hits.length + " hits.");
        for (ScoreDoc hit : hits) {
          Document d = searcher.storedFields().document(hit.doc);
          System.out.println("DocID: " + hit.doc + ", Score: " + hit.score);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static Lucene99Codec getCodec(int dimensions) {
    if (dimensions <= 1024)
      return new Lucene99Codec(Mode.BEST_SPEED);
    Lucene99Codec knnVectorsCodec = new Lucene99Codec(Mode.BEST_SPEED) {
      @Override
      public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        int maxConn = 16;
        int beamWidth = 100;
        KnnVectorsFormat knnFormat = new Lucene99HnswVectorsFormat(maxConn, beamWidth);
        return new HighDimensionKnnVectorsFormat(knnFormat, dimensions);
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
}
