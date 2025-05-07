package main

import (
	"context"
	"log"
	"math"
	"net/http"
	_ "net/http/pprof"
	"os"
	"path/filepath"
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

func simulateHighCPU(ctx context.Context) {
	matrixSize := 100
	a := calculateMatrix(matrixSize)
	b := calculateMatrix(matrixSize)
	_ = multiplyMatrices(a, b)
}

func main() {
	// 启动触发服务，用于调用main服务
	go StartServer()

	runtime.GOMAXPROCS(1)
	runtime.SetMutexProfileFraction(1)
	runtime.SetBlockProfileRate(1)

	stopChan := make(chan struct{})

	profilePath := "../profiling-data/cpu.prof"
	absPath, err := filepath.Abs(profilePath)
	if err != nil {
		log.Fatalf("无法获取绝对路径: %v", err)
	}
	log.Printf("将性能分析数据保存到: %s", absPath)

	f, createrr := os.Create(profilePath)
	if createrr != nil {
		log.Fatal("could not create CPU profile: ", createrr)
	}
	defer f.Close()

	if err := pprof.StartCPUProfile(f); err != nil {
		log.Fatal("could not start CPU profile: ", err)
	}
	log.Println("CPU profiling 已启动")
	defer pprof.StopCPUProfile()

	ch := make(chan []float64, 5)
	var mu sync.Mutex
	var sharedSlice []float64

	go func() {
		http.HandleFunc("/stop", func(writer http.ResponseWriter, request *http.Request) {
			writer.Write([]byte("Stopping the program..."))
			log.Println("停止 CPU profiling...")
			pprof.StopCPUProfile()
			log.Println("CPU profiling 已停止")
			close(stopChan)
		})

		http.HandleFunc("/hello", func(w http.ResponseWriter, r *http.Request) {
			// 模拟高CPU操作
			simulateHighCPU(r.Context())
			w.Write([]byte("Hello World"))
		})

		log.Println("主服务启动于 :8000")
		http.ListenAndServe(":8000", nil)
	}()

	go func() {
		for {
			select {
			case <-stopChan:
				return
			default:
				matrixSize := 200
				matrix1 := calculateMatrix(matrixSize)
				matrix2 := calculateMatrix(matrixSize)
				result := multiplyMatrices(matrix1, matrix2)

				data := make([]float64, 0, matrixSize*matrixSize)
				for i := range result {
					for j := range result[i] {
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

	for i := 0; i < 5; i++ {
		go func() {
			for {
				select {
				case <-stopChan:
					return
				case data := <-ch:
					for i := range data {
						for j := 0; j < 50; j++ {
							data[i] = math.Pow(data[i], 2)*math.Sin(data[i]) + math.Sqrt(math.Abs(data[i]))
						}
					}

					mu.Lock()
					defer mu.Unlock()
					sharedSlice = append(sharedSlice, data...)
					if len(sharedSlice) > 1000000 {
						sort.Float64s(sharedSlice)
						sharedSlice = sharedSlice[:1000]
					}
				}
			}
		}()

		for {
			select {
			case <-stopChan:
				return
			default:
				size := 150
				m1 := calculateMatrix(size)
				m2 := calculateMatrix(size)
				result := multiplyMatrices(m1, m2)
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
}
