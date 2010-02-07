#!/bin/bash
#param: date/time to summarize, eg 2009112205

DIR="results/$1"
FILE_LIST="$DIR/raw.*"

#2009112205
STARTSECONDS=1258884000

DATE=`echo $1 | sed -e "s/\(........\)\(..\)/\1 \2/"`
SECONDS=`date -d "$DATE" +%s`
HOURS=$(( ($SECONDS - $STARTSECONDS) / 3600 ))

cat $FILE_LIST | grep Completed | sed -e "s/^.*> //" | sort > $DIR/locations
cat $FILE_LIST | grep "Probe trace:" | sed -e "s/^.*peer UIDs=\[//" | sed -e "s/\] locs.*$//" | sed -e "s/, /\n/g" | sort -n -u > $DIR/peer_uids

#TODO: lzma raw files?
#raw files are ~ 10M in size, compress well, and aren't needed after summarizing.
#However, they should be kept in case this script changes and old data needs to
#be re-summarized.  This script would also need to be updated to fix the FILE_LIST variable.

sed -e "s/^/$HOURS\t/" $DIR/peer_uids > $DIR/peer_uids_hours

#all peers ever seen
#cat `ls results |sed -e "s/\/$//"|sort -n -r|sed -n -e "/$1/,\\$p"|sed -e "s/^/results\\//"|sed -e "s/\\$/\\/peer_uids/"` | sort -n | uniq -c > $DIR/peer_uids_cumulative

#peers seen in last 5 samples, ie 25-26 hours, ~ 1 day
cat `ls results |sed -e "s/\/$//"|sort -n -r|sed -n -e "/$1/,+4p"|sed -e "s/^/results\\//"|sed -e "s/\\$/\\/peer_uids/"` | sort -n | uniq -c > $DIR/peer_uids_5

#peers seen in past 24 samples, ie 5 days.  As a multiple of 24, this should have
#limited aliasing effects.  Some will still show through, because the samples are
#not truly instant (they take an hour).
cat `ls results |sed -e "s/\/$//"|sort -n -r|sed -n -e "/$1/,+23p"|sed -e "s/^/results\\//"|sed -e "s/\\$/\\/peer_uids/"` | sort -n | uniq -c > $DIR/peer_uids_24

#peers in last 34 samples, ie 170-171 hours, ~ 1 week; not used
#cat `ls results |sed -e "s/\/$//"|sort -n -r|sed -n -e "/$1/,+33p"|sed -e "s/^/results\\//"|sed -e "s/\\$/\\/peer_uids/"` | sort -n | uniq -c > $DIR/peer_uids_34

#summarize
echo "Time: $HOURS" > $DIR/summary

PEERS=`cat $DIR/peer_uids | wc -l`
echo "peers this sample: $PEERS" >> $DIR/summary

PEERS5=`cat $DIR/peer_uids_5 | wc -l`
echo "peers last 5: $PEERS5" >> $DIR/summary

PEERS24=`cat $DIR/peer_uids_24 | wc -l`
echo "peers last 24: $PEERS24" >> $DIR/summary

PEERS24_1=`grep "^\W*1 " $DIR/peer_uids_24 | wc -l`
echo "peers last 24, once only: $PEERS24_1" >> $DIR/summary

echo "data:	$HOURS	$PEERS	$PEERS5	$PEERS24	$PEERS24_1" >> $DIR/summary

#1 week, not used
#echo -n "peers last 34: " >> $DIR/summary
#cat $DIR/peer_uids_34 | wc -l >> $DIR/summary

#cumulative stats, not used
#PEERS_CUM=`cat $DIR/peer_uids_cumulative | wc -l`
#echo "peers so far: $PEERS_CUM" >> $DIR/summary
#echo -n "peers 1 time only: " >> $DIR/summary
#grep "^\W*1 " $DIR/peer_uids_cumulative | wc -l >> $DIR/summary

#list of samples in which a peer appears
#grep "" `find results/ -name peer_uids` | sed -e "s/results\///" | sed -e "s/\/peer_uids:/\t/" > $DIR/peer_dates
#sort -n -k 2 -k 1 $DIR/peer_dates | uniq -f 1 > $DIR/peer_first_dates
#sort -n -k 2 -k 1 -r $DIR/peer_dates | uniq -f 1 | sort -n -k 2 -k 1 > $DIR/peer_last_dates
#sort -n -k 2 -k 1 $DIR/peer_dates | uniq -u -f 1 | sort -n -k 1 -k 2 | cut -f 1 | uniq -c > $DIR/uniq_date_counts

#cut -f 1 $DIR/peer_first_dates | sort -n | uniq -c > $DIR/first_date_counts
#cut -f 1 $DIR/peer_last_dates | sort -n | uniq -c > $DIR/last_date_counts

#list of samples in which a peer appears
cat results/*/peer_uids_hours > $DIR/peer_hours

sort -n -k 2 -k 1 $DIR/peer_hours | uniq -f 1 > $DIR/peer_first_hour
sort -n -k 2 -k 1 -r $DIR/peer_hours | uniq -f 1 | sort -n -k 2 -k 1 > $DIR/peer_last_hour

sort -n -k 2 -k 1 $DIR/peer_hours | uniq -u -f 1 | sort -n -k 1 -k 2 | cut -f 1 | uniq -c > $DIR/uniq_hour_counts

rm $DIR/peer_hours

cut -f 1 $DIR/peer_first_hour | sort -n | uniq -c > $DIR/first_hour_counts
cut -f 1 $DIR/peer_last_hour | sort -n | uniq -c > $DIR/last_hour_counts

#summaries so far, in one file
cat results/*/summary | grep "data:" | sed -e "s/data:	//" | sort -n > $DIR/all_summaries

#add in churn data
join -1 1 -2 2 $DIR/all_summaries $DIR/first_hour_counts | join -1 1 -2 2 - $DIR/last_hour_counts | join -1 1 -2 2 - $DIR/uniq_hour_counts > $DIR/full_data

echo "Hours	Peers	Peers5	Peers24	Peers24_once New_nodes	Former_nodes	Unique_nodes" > $DIR/full_data_header
cat $DIR/full_data >> $DIR/full_data_header

#plot stuff
cd $DIR
gnuplot ../../plot.gnu
cd ../..

#all done!
