set terminal png size 1200,800
set output "network_size.png"
plot "full_data" using 1:2 title "Single sample size", "full_data" using 1:4 title "24 sample size", "full_data" using 1:($4-$5) title "24 sample multiple appearances"

set output "churn.png"
plot [] [0:1000] "full_data" using 1:6 title "New nodes", "full_data" using 1:7 title "Former nodes", "full_data" using 1:8 title "One-time nodes"
