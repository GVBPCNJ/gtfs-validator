#!/bin/bash
array_string=$*
echo $array_string
IFS=" " read -a my_array <<< $array_string
echo ${#my_array[@]}

for el in "${my_array[@]}"
do
   echo "$el"
   # or do whatever with individual element of the array
done
