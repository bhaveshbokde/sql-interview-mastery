-- Views over the learning dataset.
--
-- These exist so the views lesson has something real to query. A lesson that
-- says "a view is a stored query" and then shows no view is a lesson about a
-- word; this way a learner can select from one, read its definition out of
-- information_schema, and see for themselves that the definition is what
-- executes.
--
-- The playground is read-only, so nothing here can be created by a learner.
-- That is the reason views are provisioned by a migration rather than left as
-- an exercise.
--
-- ${learnerRole} is a Flyway placeholder resolved from configuration, exactly
-- as in V2. Views are covered by the default privileges granted there, but the
-- grants are repeated explicitly: relying on a default privilege set up in an
-- earlier migration to cover objects created in a later one is the kind of
-- assumption that is true until someone provisions a database by hand.

-- ---------------------------------------------------------------------------
--  learning.order_revenue
-- ---------------------------------------------------------------------------
--  Line revenue is quantity * unit_price * (1 - discount) and that expression
--  is repeated in a dozen lessons. Putting it in a view is the textbook
--  justification for one: the formula is defined in a single place, and a
--  query that uses the view cannot get it subtly wrong.
--
--  Cancelled orders are deliberately NOT excluded here. A view that silently
--  filters is a view whose users eventually ask why the numbers do not
--  reconcile with the base table; the status column is exposed so the caller
--  decides.
create view learning.order_revenue as
select o.order_id,
       o.customer_id,
       o.employee_id,
       o.order_date,
       o.ship_date,
       o.status,
       o.shipping_country,
       count(oi.product_id)                                               as line_count,
       coalesce(sum(oi.quantity), 0)                                      as units,
       coalesce(sum(oi.quantity * oi.unit_price * (1 - oi.discount)), 0)  as revenue
from learning.orders o
left join learning.order_items oi on oi.order_id = o.order_id
group by o.order_id, o.customer_id, o.employee_id, o.order_date,
         o.ship_date, o.status, o.shipping_country;

comment on view learning.order_revenue is
    'One row per order with its line count and revenue. Includes cancelled orders; filter on status.';

-- ---------------------------------------------------------------------------
--  learning.employee_directory
-- ---------------------------------------------------------------------------
--  A denormalised reading view: the employee with their department and manager
--  names resolved. Both joins are outer, because an employee may have neither.
--  This is the other common reason for a view — hiding a join that every
--  caller would otherwise write, and write slightly differently.
create view learning.employee_directory as
select e.emp_id,
       e.first_name,
       e.last_name,
       e.first_name || ' ' || e.last_name  as full_name,
       e.email,
       e.job_title,
       e.salary,
       e.hire_date,
       e.is_active,
       e.dept_id,
       d.dept_name,
       d.location,
       e.manager_id,
       m.first_name || ' ' || m.last_name  as manager_name
from learning.employees e
left join learning.departments d on d.dept_id = e.dept_id
left join learning.employees   m on m.emp_id  = e.manager_id;

comment on view learning.employee_directory is
    'Employees with department and manager names resolved. Outer joins: both may be absent.';

-- ---------------------------------------------------------------------------
--  Grants
-- ---------------------------------------------------------------------------
grant select on learning.order_revenue       to "${learnerRole}";
grant select on learning.employee_directory  to "${learnerRole}";
