#!/bin/bash
# Stop and remove the existing container if it exists
if [ "$(docker ps -q -f name=itplace-app)" ]; then
    docker stop itplace-app
    docker rm itplace-app
fi