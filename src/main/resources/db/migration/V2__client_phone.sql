-- Telefon raqam (majburiy identifikator), email ixtiyoriy
alter table clients alter column email drop not null;
alter table clients add column phone varchar(32);
alter table clients add constraint clients_phone_key unique (phone);
