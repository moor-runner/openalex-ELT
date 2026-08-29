需求：

确定一次同步计划，这次同步计划对应的文件

---

设计，契约，不变量：

通过远端的manifest文件和本地水位也就是当前entity的最新file(date,part)获取需要同步的文件列表，插入数据库

获取manifest文件时需要进行文件的错误处理，文件的count自洽url完整校验

同步计划和同步计划文件的原子性插入

增量同步任务，初次为全量同步任务，支持job和task进度状态记录，水位

file task的 insert ignore保证幂等性，sync job的插入无幂等性保证

raw数据的完整保存和校验

---

流程设计：

![image-20260812163728970](C:\Users\111\AppData\Roaming\Typora\typora-user-images\image-20260812163728970.png)

取舍：

1.获取远端文件并解析以及查询数据库采用同步：

​	数据库在本地，数据量在1000行左右，远端文件几百kb。异步大幅增加复杂度

2.sync job不存储状态字段，因为涉及到可能有空的job，没有指派任何文件，以及后续的job由file task计算得出状态

---

实现层的考虑：

DAO层的解耦
