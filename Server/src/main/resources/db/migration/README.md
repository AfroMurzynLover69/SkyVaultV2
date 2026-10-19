Ten katalog zawiera migracje Flyway.

Zasady:
- Nazwa pliku: V1__opis.sql, V2__opis.sql, V3__opis.sql ...
- Plików już wykonanych na bazie NIE edytuje się. Każda zmiana to nowy plik z kolejnym numerem.
- Tabele tworzy wyłącznie Flyway. Hibernate ma ustawione ddl-auto=validate,
  czyli tylko sprawdza zgodność encji z istniejącym schematem.
