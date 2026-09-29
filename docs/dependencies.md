# Зависимости и лицензии

Версии ниже фактически разрешены Maven для Java 11 (`mvn dependency:list -DincludeScope=runtime`). Spring Boot 2.6.3 задан требованиями конкурса, поэтому его BOM управляет транзитивными версиями Spring и PostgreSQL JDBC.

| Компонент | Версия | Лицензия / источник |
|---|---:|---|
| Spring Boot Web, Validation, JDBC | 2.6.3 | Apache-2.0; [лицензия Spring Boot](https://github.com/spring-projects/spring-boot/blob/v2.6.3/LICENSE.txt) |
| PostgreSQL JDBC | 42.3.1 (из BOM Spring Boot 2.6.3) | BSD-2-Clause; [лицензия pgJDBC](https://jdbc.postgresql.org/license/) |
| springdoc-openapi-ui | 1.7.0 | Apache-2.0; [лицензия springdoc](https://github.com/springdoc/springdoc-openapi/blob/v1.7.0/LICENSE) |
| JTS Core / GeoJSON I/O | 1.19.0 | Eclipse Distribution License 1.0 или Eclipse Public License 2.0; [справка проекта](https://github.com/locationtech/jts/blob/master/FAQ-LICENSING.md) |
| Proj4J | 1.2.2 | Apache-2.0; [лицензия Proj4J](https://github.com/locationtech/proj4j/blob/v1.2.2/LICENSE) |
| Proj4J EPSG | 1.2.2 | EPSG Database Distribution License; [условия проекта](https://github.com/locationtech/proj4j#proj4j-epsg) |
| PostgreSQL/PostGIS container | PostgreSQL 16.9 / PostGIS 3.5.2 | [образ проекта PostGIS](https://github.com/postgis/docker-postgis); тег `16-3.5` закреплён в Compose, точные версии образа проверены SQL-запросом |

Proj4J EPSG содержит часть набора EPSG. Перед распространением продукта вместе с этим набором нужно выполнить требования лицензии датасета и сохранить требуемую атрибуцию. Лицензия EPSG набора не распространяется автоматически на лицензию кода Proj4J.

Полный состав разрешённых runtime-зависимостей хранится в `target/runtime-dependencies.txt` как временный результат сборки; он не является исходным файлом проекта. Повторная проверка: `mvn dependency:list -DincludeScope=runtime`.
