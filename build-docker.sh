#!/bin/bash

docker build -f Dockerfile-softhsm-eidasmiddleware -t docker.eidastest.se:5000/demw-node-local:110-fixes-hsm .
docker push docker.eidastest.se:5000/demw-node-local:110-fixes-hsm