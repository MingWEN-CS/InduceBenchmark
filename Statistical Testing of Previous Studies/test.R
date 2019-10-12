library(ggplot2)
library(gridExtra)
library(plotly)
library(effsize)

statisticalTest <- function() {
  
  projects = c("ACCUMULO","AMBARI","LUCENE","HADOOP","JCR","OOZIE")
  types = c("sizeCompare.csv", "fileNumCompare.csv","hourCompare.csv","weekdayCompare.csv","experienceCompare.csv","timeCompare.csv","scatterCompare.csv","codeAddCompare.csv","codeDeleteCompare.csv","entropyCompare.csv")
  for (type in types) {
    df = read.csv(type)
    for (project in projects) {
      #print(project)
      szz = df$Value[df$Type=="AG-SZZ" & df$Project==project]
      oracle = df$Value[df$Type=="Oracle" & df$Project==project]
  #    print(szz)
  #    print(oracle)
      if (type=="hourCompare.csv" | type=="weekdayCompare.csv") {
        result = wilcox.test(szz, oracle, var.equal=TRUE)
  #      print(type)
      }
      else 
        result = wilcox.test(szz, oracle, var.equal=FALSE, alternative="greater")
      result2 = cohen.d(szz, oracle)
      #d = (c(szz, oracle))
      #f = (c(rep("AG-SZZ",length(szz)),rep("Oracle",length(oracle))))
      cat(result$p.value, " ", abs(result2$estimate), " ")
      #print(result2$estimate)
      #print(result2$magnitude)
      
    }
    cat("\n")
  }
}
