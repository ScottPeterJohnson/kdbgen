CREATE TABLE addresses (
  address_id BIGSERIAL PRIMARY KEY,
  state TEXT NOT NULL
);

CREATE TABLE users (
	user_id BIGSERIAL PRIMARY KEY,
	user_name TEXT NOT NULL,
	email_address TEXT NULL,
	address_id BIGINT NULL REFERENCES addresses(address_id)
);

CREATE TABLE array_test (
	array_test_id BIGSERIAL PRIMARY KEY,
	array_column TEXT[] NOT NULL
);

CREATE TYPE enum_type_test AS ENUM('test', 'test2', 'test3');
CREATE TABLE enum_test (
	enum_test_id BIGSERIAL PRIMARY KEY,
	enum_test ENUM_TYPE_TEST NOT NULL
);

CREATE TABLE common_test (
	uuid UUID PRIMARY KEY,
	timestamp TIMESTAMP DEFAULT (localtimestamp) NOT NULL
)