-- Where on a photo the part that changes sits (the bottle cap of a gel polish), as "x% y%", so the shop's square
-- cards crop around it instead of the photo's middle. Set by a variant job from the part staff marked; null = middle.
ALTER TABLE product_image ADD COLUMN focus VARCHAR(16);
