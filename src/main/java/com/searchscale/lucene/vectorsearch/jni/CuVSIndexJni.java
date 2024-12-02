package com.searchscale.lucene.vectorsearch.jni;

import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.apache.lucene.search.KnnCollector;

public class CuVSIndexJni {

  static {
    //JavaUtils.loadLibrary("libluceneraft.so");
  }

  public native byte[] initIndex(int dimension, List<float[]> vectors, int threads, int intGraphDegree, int graphDegree);

  public native void loadIndex(ZipInputStream segmentInputStream);

  public native void getTopK(float[] queryVector, int topK, int iTopK, int searchWidth, KnnCollector knnCollectior, Map<String, Integer> metaMap,
      String field, boolean isBruteForceSearch);
}
