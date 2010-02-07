#!/bin/bash
#params: date

#upload images
./upload_fcp.sh network_size.png $1 image/png | nc localhost 9481
./upload_fcp.sh churn.png $1 image/png | nc localhost 9481
./upload_fcp.sh full_data_header $1 text/plain | nc localhost 9481

#upload xhtml
#create page with links, etc
sed -e "s/DATETIME/$1/g" index.xhtml > results/$1/index.xhtml

./upload_usk.sh index.xhtml $1 application/xhtml+xml | nc localhost 9481
