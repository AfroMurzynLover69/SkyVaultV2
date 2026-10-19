This directory contains Flyway migrations.

Rules:
- File name: V1__description.sql, V2__description.sql, V3__description.sql ...
- Files already executed on the database are NOT edited. Every change is a new file with the next number.
- Tables are created exclusively by Flyway. Hibernate is set to ddl-auto=validate,
  so it only checks that entities match the existing schema.
