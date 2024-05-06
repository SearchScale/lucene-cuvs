#include <sys/time.h>

#include <cstdint>
#include <iostream>
#include <raft/core/device_mdarray.hpp>
#include <raft/core/device_resources.hpp>
#include <raft/core/resources.hpp>
#include <raft/neighbors/cagra.cuh>
#include <raft/neighbors/cagra_serialize.cuh>
#include <rmm/mr/device/device_memory_resource.hpp>
#include <rmm/mr/device/pool_memory_resource.hpp>
#include <vector>

#include "com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni.h"

// If this function is undefined it's unclear which JNI version is used. Keep
// this in sync with the runtime set in `.bazelrc`.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_21) != JNI_OK) {
    return JNI_ERR;  // JNI version not supported
  }

  // Perform any necessary initialization and registration of native methods
  // here

  return JNI_VERSION_21;  // Return the JNI version you are using
}

long ms() {
  struct timeval tp;
  gettimeofday(&tp, NULL);
  return tp.tv_sec * 1000 +
         tp.tv_usec / 1000;  // get current timestamp in milliseconds
}

raft::neighbors::cagra::index_params index_params;
raft::neighbors::cagra::search_params search_params;
raft::device_resources dev_resources;
rmm::mr::pool_memory_resource<rmm::mr::device_memory_resource> pool_mr(
    rmm::mr::get_current_device_resource(), 2 * 1024 * 1024 * 1024ull);
std::string filename("./cagra_index.indx");
raft::neighbors::cagra::index<float, uint32_t> dindx =
    raft::neighbors::cagra::index<float, uint32_t>(dev_resources);

JNIEXPORT jint JNICALL
Java_com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni_initIndex(
    JNIEnv *env, jobject jobj, jintArray docIds, jfloatArray dataVectors,
    jint numVectors, jint dimension) {
  std::cout << "CUDA devices: " << rmm::get_num_cuda_devices() << std::endl;
  rmm::mr::set_current_device_resource(&pool_mr);

  // Copy the arrays from JNI to local variables.
  long startTime = ms();
  jsize numDocs = env->GetArrayLength(docIds);
  std::vector<int> docs(numDocs);
  env->GetIntArrayRegion(docIds, 0, numDocs,
                         &docs[0]);  // TODO: This docid to index mapping should
                                     // be persisted and used during search
  std::vector<float> data(numVectors * dimension);
  env->GetFloatArrayRegion(dataVectors, 0, numVectors * dimension, &data[0]);
  auto extents = raft::make_extents<int64_t>(numVectors, dimension);
  auto dataset = raft::make_mdspan<float, int64_t>(&data[0], extents);
  std::cout << "Data copying time (CPU to GPU): " << (ms() - startTime)
            << std::endl;

  // Build the index
  startTime = ms();
  index_params.build_algo =
      raft::neighbors::cagra::graph_build_algo::NN_DESCENT;
  auto ind = raft::neighbors::cagra::build<float, uint32_t>(
      dev_resources, index_params, raft::make_const_mdspan(dataset));
  std::cout << "Cagra Index building time: " << (ms() - startTime) << std::endl;

  // Serialize the index into a file
  raft::neighbors::cagra::serialize(dev_resources, filename, ind);
  dindx = raft::neighbors::cagra::deserialize<float, uint32_t>(dev_resources,
                                                               filename);
  return numVectors * dimension;
}

JNIEXPORT jobject JNICALL
Java_com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni_getTopK(
    JNIEnv *env, jobject jobj, jfloatArray queryVector, jint topK) {
  rmm::mr::set_current_device_resource(&pool_mr);

  // Copy the query vector into the device
  int64_t topk = topK;
  int64_t n_queries = 1;
  jsize queryVectorSize = env->GetArrayLength(queryVector);
  std::vector<float> query(queryVectorSize);
  env->GetFloatArrayRegion(queryVector, 0, queryVectorSize, &query[0]);
  auto queries = raft::make_device_matrix<float, int64_t>(
      dev_resources, n_queries, queryVectorSize);  // one query at a time
  for (int i = 0; i < queryVectorSize; i++) {
    queries(0, i) = query[i];
  }

  // Perform the search
  long startTime = ms();
  auto neighbors =
      raft::make_device_matrix<uint32_t>(dev_resources, n_queries, topk);
  auto distances =
      raft::make_device_matrix<float, int64_t>(dev_resources, n_queries, topk);
  raft::neighbors::cagra::search<float, uint32_t>(
      dev_resources, search_params, dindx,
      raft::make_const_mdspan(queries.view()), neighbors.view(),
      distances.view());
  std::cout << "Time taken for cagra::search: " << (ms() - startTime)
            << std::endl;

  // Return the results (neighbors and distances)
  int numResults = distances.extent(1);
  float *retDocsAndScores =
      (float *)malloc(sizeof(int) * numResults + sizeof(float) * numResults);
  int *docs = &((int *)retDocsAndScores)[numResults];
  for (int i = 0; i < numResults; i++) {  // TODO: Is there a better SIMD
                                          // friendly copy (like thrust::copy)?
    docs[i] = neighbors(0, i);
    retDocsAndScores[i] = distances(0, i);
  }
  jobject directBuffer = env->NewDirectByteBuffer(
      (void *)retDocsAndScores,
      sizeof(int) * numResults + sizeof(float) * numResults);

  return directBuffer;
}
