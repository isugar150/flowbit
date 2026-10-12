-- Runs once, when the data volume is first created.
-- Separate database for Playwright E2E runs so they never touch development data.
CREATE DATABASE flowbit_e2e;
