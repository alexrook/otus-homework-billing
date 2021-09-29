--create extension if not exists "uuid-ossp";

drop table if exists account_stats;

create table account_stats (
    actionId bigint primary key ,
    customerId uuid,
    consumed integer,
    balance money,
    moneyMove money,
    ts bigint
);



