-- Fix the update_updated_at trigger to preserve explicitly set timestamps
-- This allows sync operations to set timestamps from file modification times

CREATE OR REPLACE FUNCTION public.update_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    -- Only set updated_at to NOW() if it's not being explicitly changed
    IF NEW.updated_at = OLD.updated_at THEN
        NEW.updated_at = NOW();
    END IF;
    RETURN NEW;
END;
$function$;
