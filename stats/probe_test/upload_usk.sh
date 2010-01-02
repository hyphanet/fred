#!/bin/bash
# params:
# filename
# date
# mime type

echo ClientHello
echo Name=Simple Upload Script
echo ExpectedVersion=2.0
echo EndMessage
echo

echo ClientPut
echo URI=key_goes_here/graphs/-1/$1
echo Metadata.ContentType=$3
echo Identifier=$1-$2
echo Global=true
echo Persistence=forever
echo UploadFrom=disk
echo Filename=/data/freenet/probe_test/results/$2/$1
echo EndMessage
echo

echo Disconnect
echo EndMessage
