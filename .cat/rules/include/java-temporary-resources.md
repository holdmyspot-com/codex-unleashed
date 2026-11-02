# Java Temporary Resources

## Design Goals

- Keep Java temporary-resource cleanup under the project's supported ownership lifecycle and keep test fixtures
	isolated.

## Guidance

Do not use `@BeforeMethod` or `@AfterMethod` to create or remove test fixtures.
