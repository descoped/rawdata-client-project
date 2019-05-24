#!/bin/sh

echo "git -C rawdata-client-api/ pull --rebase"
git -C rawdata-client-api/ pull --rebase

echo "git -C rawdata-client-provider-local-filesystem/ pull --rebase"
git -C rawdata-client-provider-local-filesystem/ pull --rebase

echo "git -C rawdata-client-provider-kafka/ pull --rebase"
git -C rawdata-client-provider-kafka/ pull --rebase

echo "git -C rawdata-state-provider-h2/ pull --rebase"
git -C rawdata-state-provider-h2/ pull --rebase

echo "git -C service-provider-api/ pull --rebase"
git -C service-provider-api/ pull --rebase
