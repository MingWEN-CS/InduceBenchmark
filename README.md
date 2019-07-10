# InduceBenchmark


This repository contains a dataset of bug-fixing commits and the associated bug-inducing commits for seven different projects that 
is used in paper "Exploring and Exploiting the Correlations between Bug-Inducing and Bug-Fixing Commits". The bitex of this paper is 
as follows:

`@inproceedings{wen2019exploring,
  title={Exploring and Exploiting the Correlations between Bug-Inducing and Bug-Fixing Commits.},
  author={Wen, Ming and Wu, Rongxin and Liu, Yepang and Tian, Yongqiang and Xie, Xuan and Cheung, Shing-Chi and Su, Zhendong},
  booktitle={Proceedings of the 2019 27th ACM Joint Meeting on European Software Engineering Conference and Symposium on the Foundations of Software Engineering},
  to appear,
  year={2019},
  organization={ACM}}`
  
This dataset is structured as follows:

`./ACCUMULO.csv` contains the information of project ACCUMULO. Each line contains the information of a bug, including `bug-ID`, `bug-fixing commit` and `bug-inducing commit`.
