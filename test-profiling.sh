#!/bin/bash
set -e  # 遇到错误立即退出

# 确保目录存在
mkdir -p profiling-data

# 清除旧的profiling数据
echo "清除旧的profiling数据..."
rm -f profiling-data/cpu.prof

# 在后台运行Go服务
echo "启动Go服务..."
cd go-service && go run main.go &
GO_PID=$!
cd ..  # 回到项目根目录

echo "Go服务已启动，PID: $GO_PID"
echo "等待30秒以收集性能数据..."

# 等待30秒
sleep 30

# 发送停止请求
echo "发送停止请求..."
curl http://localhost:8000/stop

# 等待Go程序完全退出
echo "等待Go程序退出..."
sleep 5

echo "测试完成！"
echo "生成的性能分析文件: profiling-data/cpu.prof"

# 检查文件是否存在且不为空
if [ -s profiling-data/cpu.prof ]; then
    echo "✅ 性能分析文件生成成功！"
    echo "文件大小: $(du -h profiling-data/cpu.prof | cut -f1)"
    
    # 使用go tool pprof分析性能数据
    echo "使用go tool pprof分析性能数据..."
    go tool pprof -top profiling-data/cpu.prof
else
    echo "❌ 性能分析文件生成失败或为空"
    echo "检查文件状态:"
    ls -la profiling-data/
    
    # 检查Go服务是否仍在运行
    if ps -p $GO_PID > /dev/null; then
        echo "Go服务仍在运行，强制终止..."
        kill $GO_PID
    fi
fi 