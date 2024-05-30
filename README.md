# Lucene CuVS Integration

This is an integration for [CuVS](https://github.com/rapidsai/cuvs), GPU
accelerated vector search library from NVIDIA (formerly part of [Raft](https://github.com/rapidsai/raft)),
into [Apache Lucene](https://github.com/apache/lucene).

## 🏛️ Architecture

As an initial integration, the CuVS library is plugged in as an IndexSearcher.
This project has two layers:

1. Java/JNI layer in the `lucene` directory.
2. CuVS/C++ layer in the `cuda` directory.

![Architecture](architecture.png "Lucene CuVS Architecture")

By way of a working example, OpenAI's Wikipedia corpus (25k documents) can be
indexed, each document having a content vector. A provided sample query
(query.txt) can be executed after the indexing.

> [!CAUTION]
> This is not production ready yet.

## 🚀 Benchmarks

Wikipedia (768 dimensions, 1M vectors):

|                                | Indexing   | Improvement | Search | Improvement |
| ------------------------------ | ---------- | ----------- | ------ | ----------- |
| CuVS (RTX 4090, NN_DESCENT)    | 38.80 sec  |  **25.6x**  |  2 ms  |   **4x**    |
| CuVS (RTX 2080 Ti, NN_DESCENT) | 47.67 sec  |  **20.8x**  |  3 ms  |   **2.7x**  |
| Lucene HNSW (Ryzen 7700X, single thread)      | 992.37 sec |       -     |  8 ms  |      -      |

Wikipedia (2048 dimensions, 1M vectors):

|                                           | Indexing   | Improvement |
| ----------------------------------------- | ---------- | ----------- |
| CuVS (RTX 4090, NN_DESCENT)               | 55.84 sec  |  **23.8x**  |
| Lucene HNSW (Ryzen 7950X, single thread)  | 1329.9 sec |       -     |


## ❄️ Setup

Install Nix and enable flake support. The new experimental nix installer does
this for you: <https://github.com/NixOS/experimental-nix-installer>

Install [`direnv`](https://direnv.net/) and add the `direnv hook` to your
`.bashrc`:

```bash
nix profile install nixpkgs#direnv

# For hooks into shells other than bash see https://direnv.net/docs/hook.html.
echo 'eval "$(direnv hook bash)"' >> ~/.bashrc

source ~/.bashrc
```

Now clone `lucene-cuvs`, `cd` into it and run `direnv allow`:

```bash
git clone git@github.com/SearchScale/lucene-cuvs
cd lucene-cuvs
direnv allow
```

> [!NOTE]
> If you don't want to use `direnv` you can use `nix develop` manually which is
> the command that `direnv` would automatically call for you.

Now run the example:

```bash
bazel run lucene
```

The above command will fetch the dataset, build the CUDA and Java code and run
a script which invokes a search on the dataset:

```bash
Dataset file used is: /xxx/external/_main~_repo_rules~dataset/file/dataset.zip
Index of vector field is: 5
Name of the vector field is: content_vector
Number of documents to be indexed are: 25000
Number of dimensions are: 768
Query file used is: /xxx/lucene-cuvs/lucene/query.txt
May 30, 2024 3:25:14 PM org.apache.lucene.internal.vectorization.VectorizationProvider lookup
INFO: Java vector incubator API enabled; uses preferredBitSize=256; FMA enabled
5000 docs indexed ...
10000 docs indexed ...
15000 docs indexed ...
20000 docs indexed ...
25000 docs indexed ...
Time taken for index building (end to end): 48656
Time taken for copying data from IndexReader to arrays for C++: 154
CUDA devices: 1
Data copying time (CPU to GPU): 87
[I] [15:27:45.929751] optimizing graph
[I] [15:27:47.860877] Graph optimized, creating index
Cagra Index building time: 104303
[I] [15:27:47.896854] Saving CAGRA index with dataset
Time taken for index building: 104722
Time taken for cagra::search: 7
Time taken for searching (end to end): 8
Found 5 hits.
DocID: 1461, Score: 0.12764463
DocID: 1472, Score: 0.16027361
DocID: 4668, Score: 0.16650483
DocID: 1498, Score: 0.1781094
DocID: 1475, Score: 0.18247437
```

## 🥾 Next steps

Instead of extending the IndexSearcher, create a [KnnVectorFormat](https://github.com/apache/lucene/blob/main/lucene/core/src/java/org/apache/lucene/codecs/KnnVectorsFormat.java)
and corresponding KnnVectorsWriter and KnnVectorsReader for tighter integration.

## 🌱 Contributors

* Vivek Narang, SearchScale
* Ishan Chattopadhyaya, SearchScale & Committer, Apache Lucene & Solr
* Kishore Angani, SearchScale
* Noble Paul, SearchScale & Committer, Apache Lucene & Solr
* Aaron Siddhartha Mondal, TraceMachina & Committer, NativeLink & LLVM
