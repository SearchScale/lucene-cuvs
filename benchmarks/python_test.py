import json
from sentence_transformers import SentenceTransformer, CrossEncoder, util
import time
import gzip
import os
import torch
import pylibraft
import ast
from pylibraft.neighbors import ivf_flat, ivf_pq, cagra
import csv
pylibraft.config.set_output_as(lambda device_ndarray: device_ndarray.copy_to_host())

def build_index(rows: int):
    with open('wikipedia_vector_dump.csv', newline='') as csvfile:
        print("Reading data file...")
        spamreader = csv.reader(csvfile, delimiter=',')
        article_embeddings = []

        for i, row in enumerate(spamreader):
            if i == 0:
                continue

            #r = ast.literal_eval(row[3])
            line = row[3].replace("[", "").replace("]", "").replace(" ", "").split(",")
            vec = list(map(float, line))[:768]
            article_embeddings.append(vec) 

            if i % 5000 == 0:
                print(i)

            if i == rows:
                break

        et = torch.Tensor(article_embeddings)
        print("Reading data file complete")
        params = cagra.IndexParams(build_algo='nn_descent')
        print(f"building index with {rows} number of rows.")
        start = time.time_ns()
        cagra_index = cagra.build(params, et.cuda())
        print(f"build index complete. Elapsed time: {(time.time_ns() - start)/1000000} milliseconds")
        return cagra_index

def search_raft_cagra(query, cagra_index, top_k = 5):
    search_params = cagra.SearchParams()
    print("Searching index")
    start = time.time_ns()
    hits = cagra.search(search_params, cagra_index, query.cuda(), top_k)
    print(f"Searching index complete. Elapsed time: {(time.time_ns() - start)/1000000} milliseconds")
    print(hits)

with open("query.txt", "r") as f:
    rq = ast.literal_eval(f.readline())
    query = torch.Tensor(list(rq))
    cagra_index = build_index(250000)
    #search_raft_cagra(query, cagra_index)

