#!/bin/sh

echo "git -C . pull --rebase"
git -C ./ pull --rebase

echo "git -C service-client-provider-api/ pull --rebase"
git -C service-provider-api/ pull --rebase

echo "git -C rawdata-client-api/ pull --rebase"
git -C rawdata-client-api/ pull --rebase

echo "git -C rawdata-client-payload-encryption/ pull --rebase"
git -C rawdata-client-payload-encryption/ pull --rebase

echo "git -C rawdata-client-provider-postgres/ pull --rebase"
git -C rawdata-client-provider-postgres/ pull --rebase

echo "git -C rawdata-client-provider-pulsar/ pull --rebase"
git -C rawdata-client-provider-pulsar/ pull --rebase

echo "git -C rawdata-client-provider-gcs/ pull --rebase"
git -C rawdata-client-provider-gcs/ pull --rebase

echo "git -C rawdata-client-provider-lmdb/ pull --rebase"
git -C rawdata-client-provider-lmdb/ pull --rebase

echo "git -C rawdata-copy-util/ pull --rebase"
git -C rawdata-copy-util/ pull --rebase

echo "git -C rawdata-client-provider-kafka/ pull --rebase"
git -C rawdata-client-provider-kafka/ pull --rebase

