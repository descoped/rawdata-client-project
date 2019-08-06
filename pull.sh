#!/bin/sh

echo "git -C service-client-provider-api/ pull --rebase"
git -C service-provider-api/ pull --rebase

echo "git -C rawdata-client-api/ pull --rebase"
git -C rawdata-client-api/ pull --rebase

echo "git -C rawdata-client-state-provider-h2/ pull --rebase"
git -C rawdata-client-state-provider-h2/ pull --rebase

echo "git -C rawdata-client-provider-postgres/ pull --rebase"
git -C rawdata-client-provider-postgres/ pull --rebase

echo "git -C rawdata-client-state-provider-postgres/ pull --rebase"
git -C rawdata-client-state-provider-postgres/ pull --rebase

echo "git -C rawdata-client-streams-provider-kafka/ pull --rebase"
git -C rawdata-client-streams-provider-kafka/ pull --rebase

echo "git -C rawdata-client-storage-provider-local-filesystem/ pull --rebase"
git -C rawdata-client-storage-provider-local-filesystem/ pull --rebase

echo "git -C rawdata-client-storage-provider-gcp-filestore/ pull --rebase"
git -C rawdata-client-storage-provider-gcp-filestore/ pull --rebase
