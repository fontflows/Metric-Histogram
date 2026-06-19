import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.io.File;
import java.util.*;

/**
 * Teste local do extrator de Histograma Métrico.
 *
 * <p>Lê imagens .bmp da pasta {@code test-images/}, extrai vetores de características,
 * executa k-NN usando a primeira imagem como referência e imprime os resultados.</p>
 *
 * <p>Executar via Maven: {@code mvn exec:java -Dexec.mainClass=LocalTest}</p>
 */
public class LocalTest {

    public static void main(String[] args) {
        File testDir = new File("test-images");
        File[] images = testDir.listFiles((d, n) -> n.toLowerCase().endsWith(".bmp"));

        if (images == null || images.length == 0) {
            System.out.println("Nenhuma imagem .bmp encontrada em test-images/");
            System.out.println("Baixe as imagens Brodatz e coloque-as nessa pasta.");
            return;
        }

        Arrays.sort(images, Comparator.comparing(File::getName));
        Metric_Histogram extractor = new Metric_Histogram();

        // Referencia: primeira imagem da pasta
        ImagePlus ref = IJ.openImage(images[0].getAbsolutePath());
        if (ref == null) { System.out.println("Erro ao abrir: " + images[0].getName()); return; }

        double[] refVec = extractor.extractFeatures(ref.getProcessor().convertToByteProcessor());
        ref.close();

        System.out.printf("Referencia: %s%n", images[0].getName());
        System.out.printf("Pontos de controle: %d%n", refVec.length / 2);
        System.out.println("Vetor: " + Arrays.toString(refVec));
        System.out.println();

        // Demais imagens como base de busca
        List<Metric_Histogram.ImageEntry> entries = new ArrayList<>();
        for (int i = 1; i < images.length; i++) {
            ImagePlus img = IJ.openImage(images[i].getAbsolutePath());
            if (img == null) continue;
            ByteProcessor bp = img.getProcessor().convertToByteProcessor();
            double[] vec = extractor.extractFeatures(bp);
            entries.add(new Metric_Histogram.ImageEntry(images[i].getName(), vec));
            img.close();
        }

        System.out.printf("Total de imagens de busca: %d%n%n", entries.size());

        // K-NN com as 3 funções de distância
        int k = Math.min(5, entries.size());
        for (String distFunc : new String[]{"Euclidiana", "Manhattan", "Chebyshev"}) {
            // copia para não alterar a ordem original
            List<Metric_Histogram.ImageEntry> copy = new ArrayList<>(entries);
            List<Metric_Histogram.ImageEntry> results = extractor.knnSearch(refVec, copy, k, distFunc);

            System.out.println("--- Top " + k + " vizinhos (" + distFunc + ") ---");
            for (int i = 0; i < results.size(); i++) {
                Metric_Histogram.ImageEntry e = results.get(i);
                System.out.printf("  %d. %-30s dist=%.6f  pts=%d%n",
                        i + 1, e.name, e.distance, e.vector.length / 2);
            }
            System.out.println();
        }
    }
}