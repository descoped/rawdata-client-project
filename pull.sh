#!/bin/sh

echo "git -C . pull --rebase"
git -C ./ pull --rebase

#echo "git -C service-provider-api/ pull --rebase"
#git -C service-provider-api/ pull --rebase

echo "git -C rawdata-client-api/ pull --rebase"
git -C rawdata-client-api/ pull --rebase

echo "git -C rawdata-client-provider-postgres/ pull --rebase"
git -C rawdata-client-provider-postgres/ pull --rebase

echo "git -C rawdata-client-provider-gcs/ pull --rebase"
git -C rawdata-client-provider-gcs/ pull --rebase

echo "git -C rawdata-copy-util/ pull --rebase"
git -C rawdata-copy-util/ pull --rebase

echo "git -C rawdata-crypto-util/ pull --rebase"
git -C rawdata-crypto-util/ pull --rebase

