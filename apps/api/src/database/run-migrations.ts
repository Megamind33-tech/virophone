import { readFileSync, readdirSync } from 'fs';
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
    // Discovered from disk rather than hardcoded. The previous list had to be
    // edited by hand for every new migration, and forgetting to do so skipped
    // it in silence — the deploy reported "All migrations applied successfully"
    // while the new tables did not exist.
    //
    // Filenames are zero-padded (001_, 002_, …) so a lexicographic sort is the
    // intended execution order.
    const migrationFiles = readdirSync(migrationsDir)
      .filter((f) => f.endsWith('.sql'))
      .sort();
    if (migrationFiles.length === 0) {
      throw new Error(`No migrations found in ${migrationsDir}`);
    }

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
