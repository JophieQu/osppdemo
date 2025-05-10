package main

import (
	"fmt"
	"io"
	"log"
	"net/http"

	_ "github.com/apache/skywalking-go"
)

func StartServer() {
	http.HandleFunc("/trigger", func(w http.ResponseWriter, r *http.Request) {

		resp, err := http.Get("http://localhost:8000/hello")
		if err != nil {
			http.Error(w, "调用 main.go 失败", http.StatusInternalServerError)
			return
		}
		defer resp.Body.Close()

		body, _ := io.ReadAll(resp.Body)
		fmt.Fprintf(w, "调用 main.go 成功: %s", body)
	})

	log.Println("触发服务启动于 :8090")
	log.Fatal(http.ListenAndServe(":8090", nil))
}
