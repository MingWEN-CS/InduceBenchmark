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
Be noted that a bug might contain multiple bug-inducing commits or bug-fixing commits. 

Similar cases for the other projects `AMBARI`, `HADOOP`, `JCR`, `LUCENE`, and `OOZIE`.

`./Defects4J.csv` contains the information of bug-inducing commits of the Defects4J dataset (the 91 bugs whose bug-inducing commits have been identified through software testing).

`./ACCUMULO:` this folder contains the results of the project ACCUMULO, including the `FileCoverage`, `LineCoverage`, `ActionCoverage` and the results of SZZ. It also contains the information of our experimental data, including the code evolution between bug-inducing commits and bug-fixing commits. 

Similar cases for the other projects `AMBARI`, `HADOOP`, `JCR`, `LUCENE`, and `OOZIE`.

`./Statistical Testing of Previous Studies:` this folder contains the results of `revisting previous studies`. Using R to run the script of `test.R` will generate the results of Table 2. 

`./Defects4J:` this folder contains the results of the FL on Defects4J. Each subfolder in this folder contains the information of a bug. Specifically, `ochiai.txt` contains the results of SBFL, and `induced.txt` contains the results of the boosting model using the information of bug-inducing commits. 
Be noted that when combining `ochiai` with the boosting model, remember to normalize the results of `ochiai` first.

