import { Client } from 'pg';
import { readFileSync, readdirSync } from 'fs';
import { join } from 'path';

export const DATABASE_URL =
  process.env.DATABASE_URL ||
  'postgresql://viro:viro_dev_password@localhost:5432/viro_reach';

const MIGRATIONS_DIR = join(__dirname, '../../src/database/migrations');

/**
 * Drops and recreates the public schema, then applies every SQL migration in
 * order. Reading the directory keeps this in sync as new migrations are added,
 * so entities never drift ahead of the test schema.
 */
export async function resetDatabase(): Promise<void> {
  const client = new Client({ connectionString: DATABASE_URL });
  await client.connect();
  await client.query('DROP SCHEMA public CASCADE');
  await client.query('CREATE SCHEMA public');
  await client.query('GRANT ALL ON SCHEMA public TO viro');
  await client.query('GRANT ALL ON SCHEMA public TO public');
  const files = readdirSync(MIGRATIONS_DIR)
    .filter((f) => f.endsWith('.sql'))
    .sort();
  for (const file of files) {
    await client.query(readFileSync(join(MIGRATIONS_DIR, file), 'utf-8'));
  }
  await client.end();
}
