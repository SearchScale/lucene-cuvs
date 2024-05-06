package com.searchscale.lucene.vectorsearch.jni;

import com.github.fmeum.rules_jni.RulesJni;
import java.io.File;

public class CuVSIndexJni {
  static {
    File lib = new File("cuda/cuda/cuda.so");
    System.load(lib.getAbsolutePath());
  }

  public native int initIndex(int[] docIds, float[] dataVectors, int numVectors, int dimension);
  public native Object getTopK(float[] queryVector, int topK);
}
