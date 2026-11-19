#!/bin/bash

THISDIR=`dirname $0`
LANGFILE=$THISDIR/../runelite/src/main/assets/language_list.txt

rm -f $LANGFILE
echo $THISDIR/../runelite/src/main/res/values-* | xargs -- basename -a > $LANGFILE

