package main

import "testing"

func TestVersaoPrincipal(t *testing.T) {
	casos := map[string]int{
		`openjdk version "21.0.12.1" 2026-08-18`:                                 21,
		`openjdk version "17.0.9" 2023-10-17`:                                    17,
		`java version "1.8.0_391"`:                                               8,
		`openjdk version "11" 2018-09-25`:                                        11,
		`java version "22-ea" 2024-03-19`:                                        22,
		`Picked up JAVA_TOOL_OPTIONS: -Dx=1` + "\n" + `openjdk version "17.0.2"`: 17,
		`lixo`: 0,
	}
	for saida, esperado := range casos {
		if v := versaoPrincipal(saida); v != esperado {
			t.Errorf("versaoPrincipal(%q) = %d, esperado %d", saida, v, esperado)
		}
	}
}

func TestNumeroNoNome(t *testing.T) {
	casos := map[string]int{"jdk-21.0.2+13": 21, "jdk-17.0.9.9-hotspot": 17, "jdk1.8.0_391": 8, "jre1.8.0_401": 8, "latest": 0}
	for nome, esperado := range casos {
		if n := numeroNoNome(nome); n != esperado {
			t.Errorf("numeroNoNome(%q) = %d, esperado %d", nome, n, esperado)
		}
	}
}
