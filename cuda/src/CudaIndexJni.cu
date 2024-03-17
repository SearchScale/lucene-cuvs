#include "com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni.h"
#include <cstdint>
#include <sys/time.h>
#include <vector>
#include <raft/core/device_mdarray.hpp>
#include <raft/core/device_resources.hpp>
#include <raft/core/resources.hpp>
#include <raft/neighbors/cagra.cuh>
#include <raft/neighbors/cagra_serialize.cuh>
#include <rmm/mr/device/device_memory_resource.hpp>
#include <rmm/mr/device/pool_memory_resource.hpp>

long ms () {
    struct timeval tp;
    gettimeofday(&tp, NULL);
    return tp.tv_sec * 1000 + tp.tv_usec / 1000; // get current timestamp in milliseconds
}

raft::neighbors::cagra::index_params index_params;
raft::neighbors::cagra::search_params search_params;
raft::device_resources dev_resources;
rmm::mr::pool_memory_resource<rmm::mr::device_memory_resource> pool_mr(rmm::mr::get_current_device_resource(), 2 * 1024 * 1024 * 1024ull);
std::string filename("./cagra_index.indx");
raft::neighbors::cagra::index<float, uint32_t> dindx = raft::neighbors::cagra::index<float, uint32_t>(dev_resources);

JNIEXPORT jint JNICALL Java_com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni_initIndex
(JNIEnv *env, jobject jobj, jintArray docIds, jfloatArray dataVectors, jint numVectors, jint dimension) {
  std::cout<<"CUDA devices: "<<rmm::get_num_cuda_devices()<<std::endl;
  rmm::mr::set_current_device_resource(&pool_mr);

  // Copy the arrays from JNI to local variables.
  // TODO: Instead of copying three times (JNI->array->hostmatrix->devicematrix),
  // TODO: it might possible to do it once (JNI -> Device) for better efficiency.
  long startTime = ms();
  jsize numDocs = env->GetArrayLength(docIds);
  std::vector<int> docs (numDocs);
  env->GetIntArrayRegion( docIds, 0, numDocs, &docs[0] ); // TODO: This docid to index mapping should be persisted and used during search
  std::vector<float> data(numVectors * dimension);
  env->GetFloatArrayRegion( dataVectors, 0, numVectors * dimension, &data[0] );
  auto datasetHost = raft::make_host_matrix<float, int64_t>(dev_resources, numVectors, dimension);
  auto dataset = raft::make_device_matrix<float, int64_t>(dev_resources, numVectors, dimension);
  int p = 0;
  for(size_t i = 0; i < numDocs ; i ++) {
      for(size_t j = 0; j < dimension; ++j) {
          datasetHost(i, j) = data[p++]; // TODO: Is there a better SIMD friendly way to copy?
      }
  }
  cudaStream_t stream = raft::resource::get_cuda_stream(dev_resources);
  raft::copy(dataset.data_handle(), datasetHost.data_handle(), datasetHost.size(), stream);
  raft::resource::sync_stream(dev_resources, stream);
  std::cout<<"Data copying time (CPU to GPU): "<<(ms()-startTime)<<std::endl;

  // Build the index
  startTime = ms();
  auto ind = raft::neighbors::cagra::build<float, uint32_t>(dev_resources, index_params, raft::make_const_mdspan(dataset.view()));
  std::cout << "Cagra Index building time: " << (ms()-startTime) << std::endl;

  // Serialize the index into a file
  raft::neighbors::cagra::serialize(dev_resources, filename, ind);
  dindx = raft::neighbors::cagra::deserialize<float, uint32_t>(dev_resources, filename);
  return numVectors * dimension;
}

JNIEXPORT jobject JNICALL Java_com_searchscale_lucene_vectorsearch_jni_CuVSIndexJni_getTopK
(JNIEnv *env, jobject jobj, jfloatArray queryVector, jint topK)
{
  rmm::mr::set_current_device_resource(&pool_mr);

  // Copy the query vector into the device
  int64_t topk = topK;
  int64_t n_queries = 1;
  jsize queryVectorSize = env->GetArrayLength(queryVector);
  std::vector<float> query(queryVectorSize);
  env->GetFloatArrayRegion( queryVector, 0, queryVectorSize, &query[0] );
  auto queries = raft::make_device_matrix<float, int64_t>(dev_resources, n_queries, queryVectorSize); // one query at a time
  for (int i = 0; i < queryVectorSize; i++) {
    queries(0, i) = query[i];
  }

  // Perform the search
  long startTime = ms();
  auto neighbors = raft::make_device_matrix<uint32_t>(dev_resources, n_queries, topk);
  auto distances = raft::make_device_matrix<float, int64_t>(dev_resources, n_queries, topk);
  raft::neighbors::cagra::search<float, uint32_t>(dev_resources, search_params, dindx, raft::make_const_mdspan(queries.view()), neighbors.view(), distances.view());
  std::cout<<"Time taken for cagra::search: "<<(ms()-startTime)<<std::endl;

  // Return the results (neighbors and distances)
  int numResults = distances.extent(1);
  float *retDocsAndScores = (float*)malloc( sizeof(int)*numResults + sizeof(float)*numResults );
  int               *docs = &((int*)retDocsAndScores)[numResults];
  for (int i=0; i<numResults; i++) { // TODO: Is there a better SIMD friendly copy (like thrust::copy)?
    docs[i] = neighbors(0, i);
    retDocsAndScores[i] = distances(0, i);
  }
  jobject directBuffer = env->NewDirectByteBuffer((void*)retDocsAndScores, sizeof(int)*numResults + sizeof(float)*numResults);
  return directBuffer;
}
