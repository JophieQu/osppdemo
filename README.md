# ospp预选任务

## 项目结构

```
demo-root/
├── go-service/         # Go HTTP 程序，集成 SkyWalking 和 pprof
│   ├── main.go         # Go程序主文件，创建CPU密集型负载
│   └── go.mod/go.sum   # Go依赖管理文件
├── java-analyzer/      # Java 程序，用于解析 pprof 生成的分析文件
│   ├── src/            # Java源代码目录
│   │   └── main/java/com/example/ # Java主包
│   │       ├── PprofAnalyzer.java        # 主解析程序
│   │       ├── FlameGraphGenerator.java   # 火焰图生成器
│   │       └── CallGraphGenerator.java    # 调用图生成器
│   └── pom.xml         # Maven配置文件
├── profiling-data/     # 存储性能分析结果
│   ├── cpu.prof        # CPU性能分析数据
│   ├── flamegraph.svg  # 火焰图可视化结果
│   └── callgraph.svg   # 调用图可视化结果
└── README.md           # 项目说明文档
```

## 使用说明

### 启动SkyWalking服务

   ```
   > banyand standalone   # 启动banyanDB
   > bin/oapService.sh    # OAP启动
   > bin/webappService.sh # UI启动
   ```

### 生成性能分析数据

1. 启动Go程序，并挂载skywalking-go
```
   > cd go-service
   > go build -toolexec="/Users/jingyiqu/ospp/demo/apache-skywalking-go-0.6.0-bin/bin/skywalking-go-agent-0.6.0-darwin-amd64" -a -o test .
   > ./test
   ```

2. 程序将自动生成CPU性能分析数据并保存到`profiling-data/cpu.prof`

### 解析和可视化性能分析数据

1. Maven编译并运行解析器
   ```
   > cd java-analyzer
   > mvn clean compile exec:java
   ```

3. 程序会解析`cpu.prof`文件，并生成以下可视化文件，保存到`profiling-data`目录下：
   - flamegraph.svg：CPU性能火焰图
   - callgraph.svg：函数调用关系图
   - 终端输出按照 cum 排序的前十个 HotSpot

## 结果分析

### 模拟了高cpu功耗的http程序
go-service 的 http 程序主要通过以下几个地方模拟了高 CPU 场景：

1. `/hello` 路由（在 `main.go` 中）  
   该路由的 handler 中调用了 `simulateHighCPU` 函数。该函数会生成两个 100x100 的矩阵，并进行矩阵乘法运算 `multiplyMatrices` ，这是一个计算密集型操作，会显著占用 CPU 资源。

2. 后台 goroutine（在 `main.go` 中）  
   在主函数中，除了 http 服务外，还启动了多个 goroutine，这些 goroutine 会不断地进行如下高 CPU 运算：
   - 生成大矩阵（如 200x200、150x150），并进行多次矩阵乘法。
   - 对矩阵结果中的每个元素进行大量的数学函数计算（如 `math.Pow`、`math.Sin`、`math.Sqrt`、`math.Tan`、`math.Log` 等），并多次循环嵌套，进一步增加 CPU 占用。
   - 还有一部分代码会不断地生成、处理和排序大量的浮点数切片（slice），并对其进行复杂的数学运算。

### skywalking-go UI观察

![skywalking-go](https://qqsobsidian.oss-cn-shenzhen.aliyuncs.com/20250510105527400.png)

### pprof进行cpu profiling，并对性能结果进行分析

按cum排序，输出前十个hotspots

```
Showing top 10 nodes
flat       flat%    sum%     cum        cum%     name
    0.00s    0.00%    0.00%   933.23s   32.41% runtime.main
    0.37s    0.01%    0.01%   479.52s   16.65% math.Pow
  406.38s   14.11%   14.13%   406.38s   14.11% math.pow
    0.00s    0.00%   14.13%   288.00s   10.00% math.Tan
  278.63s    9.68%   23.81%   279.99s    9.72% math.tan
    0.00s    0.00%   23.81%   110.30s    3.83% math.Log
   79.89s    2.77%   26.58%    79.95s    2.78% math.log
    0.19s    0.01%   26.59%    50.45s    1.75% math.Frexp
   39.95s    1.39%   27.97%    39.95s    1.39% math.IsInf
   33.23s    1.15%   29.13%    34.16s    1.19% main.multiplyMatrices
```

输出火焰图和函数调用图
![callgraph.svg](https://qqsobsidian.oss-cn-shenzhen.aliyuncs.com/callgraph.svg)
![flamegraph.svg](https://qqsobsidian.oss-cn-shenzhen.aliyuncs.com/flamegraph.svg)



