# Metric-Histogram

Plugin ImageJ para extração de características de imagens via **Histograma Métrico** e recuperação por **k-vizinhos mais próximos (k-NN)**.

Trabalho da disciplina de Reconhecimento de Padrões em Imagens (5954035) — USP.

---

## Estrutura do projeto

```
Metric-Histogram/
├── src/
│   ├── MetricHistogram.java   plugin principal (ImageJ PlugIn)
│   └── LocalTest.java         teste local sem abrir o ImageJ
├── test-images/               coloque aqui as imagens Brodatz (.bmp)
├── pom.xml
└── README.md
```

---

## Pré-requisitos

- Java 8+
- Maven 3.6+
- ImageJ instalado (para usar o plugin)

---

## Instalação das dependências

```bash
mvn install
```

Isso baixa o ImageJ automaticamente via Maven Central. Não é preciso instalar nada manualmente para rodar o teste local.

---

## Imagens de teste

Baixe as imagens Brodatz pelo link disponibilizado pela professora e extraia os arquivos `.bmp` dentro da pasta `test-images/`.

---

## Teste local (sem abrir o ImageJ)

Com pelo menos duas imagens `.bmp` em `test-images/`, rode:

```bash
mvn compile exec:java
```

O que acontece:
- A primeira imagem da pasta (ordem alfabética) é usada como referência.
- As demais são a base de busca.
- O extrator calcula o vetor de características de cada uma.
- O k-NN é executado com as três funções de distância (Euclidiana, Manhattan, Chebyshev) e k=5.
- Os resultados são impressos no terminal.

Exemplo de saída esperada:

```
Referencia: D001.bmp
Pontos de controle: 18
Vetor: [0.00000, 0.00412, 0.11373, 0.00389, ...]

Total de imagens de busca: 111

--- Top 5 vizinhos (Euclidiana) ---
  1. D002.bmp   dist=0.003241  pts=17
  2. D003.bmp   dist=0.005812  pts=19
  ...
```

Se você ver pontos de controle variando entre ~10 e ~50 por imagem e distâncias menores para imagens visualmente semelhantes, o algoritmo está funcionando corretamente.

---

## Como usar o plugin no ImageJ

**1. Gerar o JAR:**

```bash
mvn package
```

Isso cria `target/metric-histogram-1.0-jar-with-dependencies.jar`.

**2. Instalar no ImageJ:**

Copie o JAR gerado para a pasta `plugins/` da instalação do ImageJ.

**3. Executar:**

- Abra o ImageJ.
- Abra a imagem de referência (File > Open).
- Vá em Plugins > Metric Histogram.
- Selecione a pasta com as imagens de busca.
- Aguarde a extração dos vetores (uma barra de progresso aparece).
- Na janela que abre, informe o valor de k e escolha a função de distância.
- Clique em OK.

Os vetores de todas as imagens são salvos em `feature_vectors.txt` na pasta onde o ImageJ foi iniciado. Os resultados do k-NN são salvos em `knn_results.txt` e exibidos no Log do ImageJ (Window > Log).

---

## O algoritmo: Histograma Métrico

O histograma convencional de uma imagem em tons de cinza tem 256 bins, cada um com a frequência de pixels naquele nível. O problema é que esse vetor tem sempre 256 posições, independente do conteúdo da imagem, o que torna o vetor de características fixo e potencialmente redundante.

O Histograma Métrico resolve isso representando o histograma por um conjunto reduzido de **pontos de controle**, que são suficientes para reconstruir uma aproximação da curva original.

O processo é o seguinte:

**Passo 1 — Histograma convencional normalizado:**
Calcula-se o histograma dos 256 níveis de cinza e normaliza-se dividindo pelo total de pixels, de forma que a soma de todos os bins seja 1.

**Passo 2 — Identificação dos pontos de controle:**
O algoritmo percorre os bins sequencialmente. Para cada segmento entre dois bins, verifica se a interpolação linear entre eles representa bem a curva real, usando como critério a diferença de área entre a curva real e a reta:

```
erro = |soma real dos bins - área do trapézio formado pelos extremos|
```

Quando esse erro supera um limiar (1% por padrão), o bin anterior ao ponto de erro é registrado como um ponto de controle, e a análise reinicia a partir dele.

**Passo 3 — Vetor de características:**
Cada ponto de controle tem coordenadas (x, y), onde x é o nível de cinza (normalizado entre 0 e 1) e y é a frequência normalizada. O vetor final é a concatenação dessas coordenadas:

```
[x0, y0, x1, y1, x2, y2, ..., xn, yn]
```

O tamanho do vetor varia entre imagens conforme a complexidade do histograma. Imagens com histograma mais "acidentado" geram mais pontos de controle.

**Busca k-NN:**
Para encontrar as imagens mais semelhantes, calcula-se a distância entre o vetor da imagem de referência e o vetor de cada imagem da base. Vetores com tamanhos diferentes são alinhados com zeros à direita. As k imagens com menor distância são os vizinhos mais próximos.

Funções de distância disponíveis:
- **Euclidiana**: raiz da soma dos quadrados das diferenças (padrão, recomendada).
- **Manhattan**: soma dos valores absolutos das diferenças.
- **Chebyshev**: máximo valor absoluto entre as diferenças.

---

## Geração do Javadoc no IntelliJ

1. No menu superior, vá em **Tools > Generate JavaDoc**.
2. Em "Output directory", escolha uma pasta (por exemplo, `docs/` dentro do projeto).
3. Marque "Include JDK and library sources in -sourcepath" se quiser documentação completa.
4. Clique em **OK**.

O IntelliJ vai gerar uma pasta HTML com a documentação de todas as classes e métodos anotados com `/** ... */`.

---

## Como saber se o plugin funcionou corretamente

O plugin está funcionando se:

- Os arquivos `feature_vectors.txt` e `knn_results.txt` são criados após a execução.
- O número de pontos de controle varia entre as imagens (entre ~5 e ~60, dependendo da textura).
- As imagens retornadas como vizinhos mais próximos são visualmente parecidas com a referência (mesma textura Brodatz ou textura próxima).
- Distâncias menores indicam imagens mais parecidas; a imagem mais próxima de si mesma (se estiver na base) deve ter distância próxima de zero.
