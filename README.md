# GeoMaps

Aplicativo Android para gestão de dados espaciais e edição avançada de arquivos **KML, KMZ, GPX e Shapefile**.
Feito em Kotlin + Jetpack Compose + MapLibre Native. Uso pessoal/interno (APK).

## Como compilar

### Pelo GitHub (recomendado)
Cada `push` na branch `main` dispara o workflow **Compilar APK** (`.github/workflows/android.yml`):

1. O APK fica em **Releases** (`GeoMaps.apk`) e também em *Actions → Artifacts*.
2. O resultado (sucesso ou erros) é comentado na issue **"Relatório de compilação"**.

### No computador
Requer JDK 17 e Android SDK (Android Studio).
```
gradlew assembleDebug
```
O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

> A assinatura usa uma chave fixa (`app/geomaps.keystore.b64`), para que cada APK novo
> instale por cima do anterior sem perder os dados. O ícone fica em `app/binres` (texto base64).

## Primeiros passos no app

1. Permita a localização e as notificações.
2. **Configurações**:
   - As camadas do Google já vêm com a chave do projeto (somente online); é possível trocar a chave.
   - Chave **ArcGIS** para baixar o Esri World Imagery offline pelo serviço oficial de exportação.
   - Datum (SIRGAS 2000), sistema de coordenadas e fuso UTM fixo (ex.: 24S / 25S no RN).
3. **Mapas offline e MDE**: baixe áreas do OSM vetorial, do Esri e o modelo de elevação
   (Copernicus GLO-30 ou SRTM). O cache é limitado a 2 GB, apagando as áreas mais antigas.

## Funcionalidades

- **Arquivos**: GPX, KML, KMZ (com ícones, cores e fotos), Shapefile (.zip), GeoJSON, GeoPackage, CSV; exportação também em DXF (UTM, para CAD).
- **Importação seletiva** por pasta/tipo, para projeto existente ou novo.
- **Meus Mapas**: projetos com visibilidade sobreposta, projeto ativo, renomear, duplicar, mesclar, exportar, compartilhar, formulários de atributos e histórico de versões.
- **Edição**: trilha e área manuais, vértices (mover, inserir, excluir, intervalo, por coordenada), snapping, desfazer/refazer, mesclar, dividir, inverter, simplificar (Douglas-Peucker), filtrar pontos ruins do GPS, buffer, desenho à mão livre (imagem ou linhas).
- **Waypoints**: GPS, coordenada (UTM, graus decimais, GMS), toque longo no mapa, fotos com EXIF, ícones e cores, atributos copiáveis, lista com busca.
- **Altimetria**: perfil interativo ligado ao mapa, altitude do GPS ou do MDE, estatísticas de trilha.
- **Bases**: Google (Padrão, Terreno, Satélite), Esri World Imagery, OSM (padrão, HOT, OpenTopoMap), OSM vetorial e camadas WMS/WMTS/XYZ personalizadas.
- **GPS**: gravação em segundo plano, seguir posição, rotação pelo rumo, telemetria, GNSS externo via Bluetooth (NMEA/RTK), ir até ponto, seguir trilha com alerta de desvio.
- **Dados**: backup/restauração (`.geomaps`, pode ser salvo no Google Drive) e backup automático diário.

## Estrutura

```
app/src/main/java/br/geomaps/
  core/      Kotlin puro: modelo, formatos, projeções, MDE, NMEA, operações (testável na JVM)
  data/      SQLite, configurações, mídia, GeoPackage, backup, buffer (JTS)
  location/  GPS, GNSS Bluetooth, bússola, serviço de gravação
  map/       MapLibre, bases, offline, MDE, fontes dos rótulos
  ui/        Telas Compose
```

A especificação completa está no projeto "GeoMapas" (Claude), documento GeoMaps_Especificacao.md.
