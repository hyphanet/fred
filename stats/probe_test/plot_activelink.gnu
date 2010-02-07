set terminal png size 108,36
set output "activelink.png"
unset tics
set bmargin 0
set lmargin 0
set tmargin 0
set rmargin 0
set pointsize 0.1
plot [0:800] [0:20000] "full_data" using 1:2 notitle "Single sample size", "full_data" using 1:4 notitle "24 sample size", "full_data" using 1:($4-$5) notitle "24 sample multiple appearances"
