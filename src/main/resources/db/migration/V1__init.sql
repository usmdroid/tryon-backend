-- Do'kon (hamkor) akkauntlari
create table clients (
    id            uuid         primary key,
    name          varchar(255) not null,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    created_at    timestamptz  not null default now()
);
