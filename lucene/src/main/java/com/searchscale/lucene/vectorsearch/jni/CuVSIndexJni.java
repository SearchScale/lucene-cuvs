package com.searchscale.lucene.vectorsearch.jni;

public class CuVSIndexJni {

  static {
    JavaUtils.loadLibrary("libluceneraft.so");
  }

  public native int initIndex(int[] docIds, float[] dataVectors, int numVectors, int dimension);
  public native Object getTopK(float[] queryVector, int topK);
}

