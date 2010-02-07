#!/bin/bash

#usage: ./probe_test.sh N
#N = number of probes to run per process
#5 processes total are run, so eg N=120 results in 600 probes total
#probes are sent every 30 seconds per process, so N=120 takes 1 hour

DATE_TIME=`date -u +%Y%m%d%H`
SEED=`date -u +%y%m%d%H`
DIR="results/$DATE_TIME"
mkdir $DIR
FILE_LIST="$DIR/raw.1$SEED $DIR/raw.2$SEED $DIR/raw.3$SEED $DIR/raw.4$SEED $DIR/raw.5$SEED"

echo date: $DATE_TIME > $DIR/raw.1$SEED
echo date: $DATE_TIME > $DIR/raw.2$SEED
echo date: $DATE_TIME > $DIR/raw.3$SEED
echo date: $DATE_TIME > $DIR/raw.4$SEED
echo date: $DATE_TIME > $DIR/raw.5$SEED

echo "$1 probes per process, 5 processes."

#each process waits 30s between sending probes, so 30/5=6 s delay between processes
java -ea ProbeTester 1$SEED $1 |telnet localhost 2323 >> $DIR/raw.1$SEED &
sleep 6
java -ea ProbeTester 2$SEED $1 |telnet localhost 2323 >> $DIR/raw.2$SEED &
sleep 6
java -ea ProbeTester 3$SEED $1 |telnet localhost 2323 >> $DIR/raw.3$SEED &
sleep 6
java -ea ProbeTester 4$SEED $1 |telnet localhost 2323 >> $DIR/raw.4$SEED &
sleep 6
java -ea ProbeTester 5$SEED $1 |telnet localhost 2323 >> $DIR/raw.5$SEED &

wait

#compute stats
./summarize.sh $DATE_TIME

#upload graphs etc
#output = normal responses from node; ignore
./upload.sh $DATE_TIME > /dev/null
