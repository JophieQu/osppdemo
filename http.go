package main

import (
	"log"
	"math"
	"net/http"
	_ "net/http/pprof"
	"os"
	"runtime"
	"runtime/pprof"
	"sort"
	"sync"

	_ "github.com/apache/skywalking-go"
)

func calculateMatrix(size int) [][]float64 {
	matrix := make([][]float64, size)
	for i := range matrix {
		matrix[i] = make([]float64, size)
		for j := range matrix[i] {
			matrix[i][j] = float64(i*j) + math.Sqrt(float64(i+j))
		}
	}
	return matrix
}

func multiplyMatrices(a, b [][]float64) [][]float64 {
	size := len(a)
	result := make([][]float64, size)
	for i := range result {
		result[i] = make([]float64, size)
		for j := range result[i] {
			for k := 0; k < size; k++ {
				result[i][j] += a[i][k] * b[k][j] //cpu profiling
			}
		}
	}
	return result
}

func main() {
	runtime.GOMAXPROCS(1)
	runtime.SetMutexProfileFraction(1)
	runtime.SetBlockProfileRate(1)

	// 创建用于停止程序的channel
	stopChan := make(chan struct{})

	f, createrr := os.Create("cpu.prof")
	if createrr != nil {
		log.Fatal("could not create CPU profile: ", createrr)
	}
	defer f.Close()

	if err := pprof.StartCPUProfile(f); err != nil {
		log.Fatal("could not start CPU profile: ", err)
	}
	defer pprof.StopCPUProfile()

	// 创建channel和互斥锁
	ch := make(chan []float64, 5)
	var mu sync.Mutex
	var sharedSlice []float64

	// 启动HTTP服务器
	go func() {

		http.HandleFunc("/hello", func(writer http.ResponseWriter, request *http.Request) {
			writer.Write([]byte("Hello World"))
		})

		httperr := http.ListenAndServe(":8000", nil)
		if httperr != nil && httperr != http.ErrServerClosed {
			log.Fatal("HTTP server error: ", httperr)
		}
	}()

	// 启动生产者goroutine，进行CPU密集型计算
	go func() {
		for {
			select {
			case <-stopChan:
				return
			default: // cpu profiling
				// 创建并计算大型矩阵
				matrixSize := 200
				matrix1 := calculateMatrix(matrixSize)
				matrix2 := calculateMatrix(matrixSize)
				result := multiplyMatrices(matrix1, matrix2)

				// 将结果转换为一维数组并发送到channel
				data := make([]float64, 0, matrixSize*matrixSize)
				for i := range result {
					for j := range result[i] {
						// 增加每个元素的计算复杂度
						val := result[i][j]
						for k := 0; k < 100; k++ {
							val = math.Pow(math.Sin(val), 2) + math.Cos(val)
						}
						data = append(data, val)
					}
				}
				select {
				case ch <- data:
				case <-stopChan:
					return
				}
			}
		}
	}()

	// 启动多个消费者goroutine，进行数学计算和内存操作
	for i := 0; i < 5; i++ {
		go func() {
			for {
				select {
				case <-stopChan:
					return
				case data := <-ch:
					// 进行额外的CPU密集型计算
					for i := range data {
						// 增加计算复杂度
						for j := 0; j < 50; j++ {
							data[i] = math.Pow(data[i], 2)*math.Sin(data[i]) + math.Sqrt(math.Abs(data[i]))
						}
					}

					// 锁竞争区域
					mu.Lock()
					defer mu.Unlock()
					sharedSlice = append(sharedSlice, data...)
					if len(sharedSlice) > 1000000 {
						// 对切片进行排序，增加CPU消耗
						sort.Float64s(sharedSlice) //cpu profiling
						sharedSlice = sharedSlice[:1000]
					}
				}
			}
		}()
	}

	// 主goroutine也参与计算
	for {
		select {
		case <-stopChan:
			return
		default:
			// 进行CPU密集型计算
			size := 150
			m1 := calculateMatrix(size)
			m2 := calculateMatrix(size)
			result := multiplyMatrices(m1, m2)

			// 对结果进行额外计算
			for i := range result {
				for j := range result[i] {
					for k := 0; k < 100; k++ {
						result[i][j] = math.Pow(math.Tan(result[i][j]), 2) + math.Log(math.Abs(result[i][j])+1)
					}
				}
			}
		}
	}
}
