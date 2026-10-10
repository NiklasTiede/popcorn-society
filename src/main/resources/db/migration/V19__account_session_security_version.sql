alter table account add column security_version bigint not null default 0;

-- Legacy principals deserialize with version zero. Require a fresh login on upgrade,
-- including sessions whose account permissions changed before versioning existed.
update account set security_version = 1;

-- Persist revocation even if a locked/disabled account is subsequently reactivated.
create function advance_account_security_version() returns trigger language plpgsql as $$
begin
    new.security_version := old.security_version + 1;
    return new;
end;
$$;

create trigger account_security_state_changed
before update of locked, enabled on account
for each row
when (old.locked is distinct from new.locked or old.enabled is distinct from new.enabled)
execute function advance_account_security_version();
