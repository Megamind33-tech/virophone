import { readFileSync } from 'fs';
import { join } from 'path';
import { Client } from 'pg';

async function runMigrations() {
  const databaseUrl =
    process.env.DATABASE_URL ||
    'postgresql://viro:viro_dev_password@localhost:5432/viro_reach';

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  try {
    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version VARCHAR(50) PRIMARY KEY,
        applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
    `);

    const migrationsDir = join(__dirname, 'migrations');
    const migrationFiles = ['001_initial_schema.sql', '002_offline_trust.sql'];

    for (const file of migrationFiles) {
      const version = file.replace('.sql', '');
      const { rows } = await client.query(
        'SELECT version FROM schema_migrations WHERE version = $1',
        [version],
      );

      if (rows.length > 0) {
        console.log(`Migration ${version} already applied, skipping`);
        continue;
      }

      const sql = readFileSync(join(migrationsDir, file), 'utf-8');
      await client.query('BEGIN');
      try {
        await client.query(sql);
        await client.query(
          'INSERT INTO schema_migrations (version) VALUES ($1)',
          [version],
        );
        await client.query('COMMIT');
        console.log(`Applied migration: ${version}`);
      } catch (err) {
        await client.query('ROLLBACK');
        throw err;
      }
    }

    console.log('All migrations applied successfully');
  } finally {
    await client.end();
  }
}

runMigrations().catch((err) => {
  console.error('Migration failed:', err);
  process.exit(1);
});
