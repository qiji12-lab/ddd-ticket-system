$env:JAVA_HOME = "D:\_cgb\专业\java\IntelliJ IDEA 2025.3.4\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location "D:\_cgb\项目\轻量级工单管理系统\DDD"
& "D:\dev\apache-maven-3.9.6\bin\mvn.cmd" spring-boot:run
