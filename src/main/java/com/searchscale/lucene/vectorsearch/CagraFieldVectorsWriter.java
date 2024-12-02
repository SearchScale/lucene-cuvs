package com.searchscale.lucene.vectorsearch;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.index.FieldInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CagraFieldVectorsWriter extends KnnFieldVectorsWriter<float[]> {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public String fieldName = null;
  public List<float[]> vectors = null;
  public int fieldVectorDimension = -1;

  public CagraFieldVectorsWriter(FieldInfo fieldInfo) {
    this.fieldName = fieldInfo.getName();
    this.fieldVectorDimension = fieldInfo.getVectorDimension();
    vectors = new ArrayList<float[]>();
  }

  @Override
  public long ramBytesUsed() {
    return fieldName.getBytes().length + Integer.BYTES + (vectors.size() * fieldVectorDimension * Float.BYTES);
  }

  @Override
  public void addValue(int docID, float[] vectorValue) throws IOException {
    vectors.add(vectorValue);
  }

  @Override
  public float[] copyValue(float[] vectorValue) {
    throw new UnsupportedOperationException();
  }

}
